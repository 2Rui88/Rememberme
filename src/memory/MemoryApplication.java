package memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MemoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(MemoryApplication.class, args);
    }
}
