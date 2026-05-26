package com.resumeshaper.config;

import com.resumeshaper.auth.JwtAuthFilter;
import com.resumeshaper.auth.OAuth2SuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final AppProperties appProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers(HttpMethod.GET,  "/api/roles").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/guest/session").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/resume/upload").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/resume/process").permitAll()
                        .requestMatchers(HttpMethod.GET,  "/api/resume/download/**").permitAll()
                        .requestMatchers(HttpMethod.GET,  "/api/resume/status/**").permitAll()
                        .requestMatchers(HttpMethod.GET,  "/api/resume/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/resume/{jobId}/reshape").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/jd/analyze").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/latex/compile").permitAll()
                        .requestMatchers("/api/auth/refresh").permitAll()
                        .requestMatchers("/api/auth/me").permitAll()
                        .requestMatchers("/api/auth/oauth2/**").permitAll()
                        .requestMatchers("/api/auth/otp/send").permitAll()
                        .requestMatchers("/api/auth/otp/verify").permitAll()
                        .requestMatchers("/api/latex/reshape/**").permitAll()
                        .requestMatchers("/api/auth/claim-session").authenticated() // AuthController -> claim-session
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Everything else requires auth
                        .anyRequest().authenticated()
                )
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(ep ->
                    ep.baseUri("/api/auth/oauth2/authorize"))
                .redirectionEndpoint(ep ->
                    ep.baseUri("/api/auth/oauth2/callback/*"))
                .successHandler(oAuth2SuccessHandler)
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(appProperties.getCors().getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "X-Guest-Token"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }
}
