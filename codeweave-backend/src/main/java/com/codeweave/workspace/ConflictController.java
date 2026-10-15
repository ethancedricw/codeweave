package com.codeweave.workspace;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/workspace/conflicts")
public class ConflictController {
  private static final String PROJECT_HASH="cw:workspace:projects";
  private final StringRedisTemplate redis; private final ObjectMapper mapper;
  public ConflictController(StringRedisTemplate redis,ObjectMapper mapper){this.redis=redis;this.mapper=mapper;}
  @GetMapping public Map<String,Object> list(){ StpUtil.checkLogin(); List<Map<String,Object>> out=new ArrayList<>(); String uid=StpUtil.getLoginIdAsString();
    for(Object raw:redis.opsForHash().values(PROJECT_HASH)){Map<String,Object> p=parse(raw); if(p==null||!member(p,uid))continue; String root=String.valueOf(p.getOrDefault("serverPath","")).trim(); if(root.isBlank())continue;
      try{for(String file:git(root,"diff","--name-only","--diff-filter=U","-z")){Map<String,Object> x=new LinkedHashMap<>();x.put("projectId",p.get("id"));x.put("projectName",p.get("name"));x.put("file",file);x.put("path",root);x.put("status","unmerged");out.add(x);}}catch(Exception e){/* unavailable workspaces are omitted */}}
    return Map.of("items",out,"count",out.size()); }
  @PostMapping("/{projectId}/resolve") public Map<String,Object> resolve(@PathVariable String projectId,@RequestBody Map<String,Object> body){StpUtil.checkLogin(); Map<String,Object> p=project(projectId); if(p==null||!member(p,StpUtil.getLoginIdAsString()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权访问该项目"); String file=String.valueOf(body.getOrDefault("file","")).trim(); if(file.isBlank()||file.contains("\0"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"文件路径不能为空"); String root=String.valueOf(p.getOrDefault("serverPath","")); Path base=Paths.get(root).toAbsolutePath().normalize(), target=base.resolve(file).normalize(); if(!target.startsWith(base))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"非法文件路径");
    try{List<String> unresolved=git(root,"diff","--name-only","--diff-filter=U","-z"); if(!unresolved.contains(file))throw new ResponseStatusException(HttpStatus.CONFLICT,"该文件已无未合并状态"); run(root,"add","-A","--",file); return Map.of("ok",true,"file",file,"resolvedAt",Instant.now().toString());}catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Git 处理失败: "+e.getMessage());}}
  private Map<String,Object> project(String id){Object raw=redis.opsForHash().get(PROJECT_HASH,id);return parse(raw);}
  private boolean member(Map<String,Object> p,String uid){Object raw=p.get("members"); if(raw instanceof Collection<?> c)for(Object o:c)if(o instanceof Map<?,?> m&&uid.equals(String.valueOf(m.get("userId"))))return true; return uid.equals(String.valueOf(p.getOrDefault("ownerId","demo-user")));}
  private Map<String,Object> parse(Object v){try{return v==null?null:mapper.readValue(String.valueOf(v),new TypeReference<Map<String,Object>>(){});}catch(Exception e){return null;}}
  private List<String> git(String dir,String...args)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();List<String> a=new ArrayList<>();a.add("git");a.addAll(List.of(args));Process p=new ProcessBuilder(a).directory(new File(dir)).redirectErrorStream(true).start();try(InputStream in=p.getInputStream()){in.transferTo(b);}if(!p.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Git 超时");}if(p.exitValue()!=0)throw new IOException(b.toString(StandardCharsets.UTF_8));String text=new String(b.toByteArray(),StandardCharsets.UTF_8);List<String> out=new ArrayList<>();for(String part:text.split("\\\\u0000"))if(!part.isBlank())out.add(part);return out;}
  private void run(String dir,String...args)throws Exception{List<String>a=new ArrayList<>();a.add("git");a.addAll(List.of(args));Process p=new ProcessBuilder(a).directory(new File(dir)).redirectErrorStream(true).start();if(!p.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Git 超时");}if(p.exitValue()!=0)throw new IOException(new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8));}
}

