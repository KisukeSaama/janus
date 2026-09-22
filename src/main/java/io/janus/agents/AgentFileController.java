package io.janus.agents;

import java.util.UUID;

import org.springframework.web.bind.annotation.*;

/** {@code JANUS.md}, as the console downloads it. The MCP tool reads the same service. */
@RestController
@RequestMapping("/api/admin/agent-file")
public class AgentFileController {
    private final AgentFileService files;

    public AgentFileController(AgentFileService files) {
        this.files = files;
    }

    @GetMapping
    public AgentFileService.Rendered render(@RequestParam(required = false) UUID applicationId) {
        return files.render(applicationId);
    }
}
