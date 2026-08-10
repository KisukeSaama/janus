package io.janus.audit;

import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/audit-events")
public class AuditAdminController {
    private final AuditEventRepository repository;
    public AuditAdminController(AuditEventRepository repository){this.repository=repository;}
    @GetMapping public Page<AuditEvent> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return repository.findAllByOrderByOccurredAtDesc(PageRequest.of(Math.max(0,page),Math.min(Math.max(size,1),200)));}
}
