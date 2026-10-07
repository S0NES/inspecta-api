package br.com.vistoria.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "br.com.vistoria.core", considerNestedRepositories = true)
public class JpaConfig {
}
