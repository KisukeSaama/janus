package io.janus.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){ return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService users(@Value("${janus.admin.username}") String user, @Value("${janus.admin.password}") String password, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(user).password(encoder.encode(password)).roles("ADMIN").build());
    }
    @Bean @Order(1) SecurityFilterChain gateway(HttpSecurity http, ApiKeyAuthenticationFilter apiKey) throws Exception {
        return http.securityMatcher("/gateway/**").csrf(c->c.disable()).cors(Customizer.withDefaults())
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.anyRequest().authenticated())
                .addFilterBefore(apiKey, BasicAuthenticationFilter.class).build();
    }
    @Bean @Order(2) SecurityFilterChain admin(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).cors(Customizer.withDefaults()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers("/actuator/health/**").permitAll().requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults()).build();
    }
    @Bean CorsConfigurationSource cors(@Value("${janus.cors-origins}") String origins) {
        var c=new CorsConfiguration(); c.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        c.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS")); c.setAllowedHeaders(List.of("Authorization","Content-Type")); c.setAllowCredentials(false); c.setMaxAge(3600L);
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/admin/**",c); return source;
    }
}
