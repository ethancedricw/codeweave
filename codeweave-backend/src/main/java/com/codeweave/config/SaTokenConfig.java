package com.codeweave.config;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.jwt.StpLogicJwtForMixin;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
@Configuration public class SaTokenConfig implements WebMvcConfigurer {
  @Bean public StpLogicJwtForMixin jwtStpLogic(){
    StpLogicJwtForMixin logic = new StpLogicJwtForMixin();
    SaManager.putStpLogic(logic);
    return logic;
  }
  @Bean public SaInterceptor saInterceptor(){ return new SaInterceptor(handle -> {}); }
  public void addInterceptors(InterceptorRegistry r){ r.addInterceptor(saInterceptor()).addPathPatterns("/api/**").excludePathPatterns("/api/auth/login","/api/health"); }
  @Bean public WebMvcConfigurer codeWeaveCors(){ return new WebMvcConfigurer() {
    @Override public void addCorsMappings(CorsRegistry registry){ registry.addMapping("/api/**").allowedOriginPatterns("http://localhost:[*]","http://127.0.0.1:[*]","https://*.natappfree.cc","http://*.natappfree.cc").allowedMethods("GET","POST","PUT","DELETE","OPTIONS").allowedHeaders("*").allowCredentials(true); }
  }; }
}
