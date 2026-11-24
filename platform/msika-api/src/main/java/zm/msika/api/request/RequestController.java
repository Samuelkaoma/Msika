package zm.msika.api.request;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.request.RequestKind;
import zm.msika.core.request.RequestPolicy;
import zm.msika.core.user.Role;

/** The approvals inbox (card M-62). Who may raise or decide each request is checked in {@link RequestService}. */
@RestController
@RequestMapping("/api/v1/councils/{councilId}")
class RequestController {

    private final RequestService requests;

    RequestController(RequestService requests) {
        this.requests = requests;
    }

    record RaiseRequest(String kind, UUID subjectId, Map<String, String> effect, String reason,
            List<RequestService.Evidence> evidence) {}

    record DecisionRequest(String decision, String reason, UUID correctsDecisionId) {}

    record BulkRequest(List<UUID> requestIds, String reason) {}

    record HandOverRequest(UUID toUserId) {}

    record HandOverResponse(int moved) {}

    record PolicyRequest(List<Set<Role>> steps, Integer deadlineDays, Boolean bulkAllowed, Integer sampleRate) {}

    record PolicyResponse(RequestKind kind, RequestPolicy policy, RequestPolicy minimum) {}

    @GetMapping("/inbox")
    List<RequestService.Item> inbox(@PathVariable UUID councilId, @RequestParam(defaultValue = "mine") String queue) {
        return requests.inbox(councilId, queue);
    }

    @GetMapping("/requests/{id}")
    RequestService.Detail open(@PathVariable UUID councilId, @PathVariable UUID id) {
        return requests.open(councilId, id);
    }

    /** 201 for a new request, 200 when the Idempotency-Key was used before for the same one. */
    @PostMapping("/requests")
    ResponseEntity<RequestService.Detail> raise(@PathVariable UUID councilId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody RaiseRequest body) {
        if (body.subjectId() == null) {
            throw new InvalidInputException("subjectId is required");
        }
        RequestService.Raised raised = requests.raise(councilId, RequestService.kind(body.kind()), body.subjectId(),
                body.effect() == null ? Map.of() : body.effect(), body.reason(),
                body.evidence() == null ? List.of() : body.evidence(), key);
        return raised.created()
                ? ResponseEntity.created(URI.create("/api/v1/councils/" + councilId + "/requests/" + raised.detail().request().id()))
                        .body(raised.detail())
                : ResponseEntity.ok(raised.detail());
    }

    @PostMapping("/requests/{id}/decisions")
    RequestService.Detail decide(@PathVariable UUID councilId, @PathVariable UUID id, @RequestBody DecisionRequest body) {
        boolean approve = "APPROVE".equals(body.decision());
        if (!approve && !"REJECT".equals(body.decision())) {
            throw new InvalidInputException("decision must be APPROVE or REJECT");
        }
        return requests.decide(councilId, id, approve, body.reason(), body.correctsDecisionId());
    }

    @PostMapping("/requests/{id}/withdraw")
    RequestService.Detail withdraw(@PathVariable UUID councilId, @PathVariable UUID id) {
        return requests.withdraw(councilId, id);
    }

    @PostMapping("/requests/bulk-decisions")
    List<RequestService.BulkResult> bulk(@PathVariable UUID councilId, @RequestBody BulkRequest body) {
        if (body.requestIds() == null || body.requestIds().contains(null)) {
            throw new InvalidInputException("requestIds is required");
        }
        return requests.bulkApprove(councilId, body.requestIds(), body.reason());
    }

    @PostMapping("/requests/hand-over")
    HandOverResponse handOver(@PathVariable UUID councilId, @RequestBody HandOverRequest body) {
        if (body.toUserId() == null) {
            throw new InvalidInputException("toUserId is required");
        }
        return new HandOverResponse(requests.handOver(councilId, body.toUserId()));
    }

    @GetMapping("/policies")
    List<PolicyResponse> policies(@PathVariable UUID councilId) {
        return requests.policies(councilId).entrySet().stream()
                .map(e -> new PolicyResponse(e.getKey(), e.getValue(), e.getKey().minimum())).toList();
    }

    @PutMapping("/policies/{kind}")
    PolicyResponse setPolicy(@PathVariable UUID councilId, @PathVariable("kind") String name, @RequestBody PolicyRequest body) {
        RequestKind kind = RequestService.kind(name);
        if (body.steps() == null || body.steps().contains(null) || body.deadlineDays() == null || body.bulkAllowed() == null
                || body.sampleRate() == null) {
            throw new InvalidInputException("steps, deadlineDays, bulkAllowed and sampleRate are all required");
        }
        RequestPolicy policy = requests.setPolicy(councilId, kind, new RequestPolicy(body.steps(), body.deadlineDays(),
                body.bulkAllowed(), body.sampleRate()));
        return new PolicyResponse(kind, policy, kind.minimum());
    }
}
