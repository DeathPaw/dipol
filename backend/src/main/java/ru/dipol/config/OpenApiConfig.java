package ru.dipol.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI dipolOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ERP «Диполь»")
                .version("0.1")
                .description("Заявки, выезды, материалы, ФОТ, документы, ТБ, транспорт и кассы"));
    }
}
