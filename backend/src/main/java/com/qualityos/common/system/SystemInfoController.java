package com.qualityos.common.system;

import com.qualityos.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System")
public class SystemInfoController {

    private final String version;

    SystemInfoController(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "development";
    }

    @GetMapping("/info")
    @Operation(summary = "Application name and version")
    ApiResponse<SystemInfo> info() {
        return ApiResponse.ok(new SystemInfo("QualityOS", version, "v1"));
    }

    public record SystemInfo(String application, String version, String apiVersion) {
    }
}
