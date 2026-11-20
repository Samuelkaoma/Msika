package zm.msika.api.admin;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.core.agent.Agent;
import zm.msika.core.trader.Trader;

/**
 * Traders, agents and stall plates, for a council's admins (and read-only for its officers).
 *
 * <p>Responses carry the full phone number because the council registered it and needs to see it. The
 * number is never written to a log in full (see {@link PeopleAdminService}).
 */
@RestController
class PeopleController {

    private final PeopleAdminService service;

    PeopleController(PeopleAdminService service) {
        this.service = service;
    }

    record RegisterTraderRequest(String phone, String displayName, String language) {

        /** Never prints the phone number, so it cannot reach a log by accident. */
        @Override
        public String toString() {
            return "RegisterTraderRequest[displayName=" + displayName + ", language=" + language + "]";
        }
    }

    record TraderResponse(
            UUID id, UUID councilId, String phone, String displayName, String language,
            Instant consentLevySweepAt, Instant consentDataAt) {
        static TraderResponse of(Trader t) {
            return new TraderResponse(t.id(), t.councilId(), t.phone().e164(), t.displayName(),
                    t.language().code(), t.consentLevySweepAt(), t.consentDataAt());
        }
    }

    record RegisterAgentRequest(String phone, String displayName) {

        /** Never prints the phone number. */
        @Override
        public String toString() {
            return "RegisterAgentRequest[displayName=" + displayName + "]";
        }
    }

    record AgentResponse(UUID id, UUID councilId, String phone, String displayName, boolean active) {
        static AgentResponse of(Agent a) {
            return new AgentResponse(a.id(), a.councilId(), a.phone().e164(), a.displayName(), a.active());
        }
    }

    @PostMapping("/api/v1/councils/{councilId}/traders")
    ResponseEntity<TraderResponse> registerTrader(
            @PathVariable UUID councilId, @RequestBody RegisterTraderRequest request) {
        Trader trader = service.registerTrader(councilId, request.phone(), request.displayName(), request.language());
        return ResponseEntity.created(URI.create("/api/v1/councils/" + councilId + "/traders/" + trader.id()))
                .body(TraderResponse.of(trader));
    }

    @GetMapping("/api/v1/councils/{councilId}/traders")
    List<TraderResponse> listTraders(@PathVariable UUID councilId) {
        return service.listTraders(councilId).stream().map(TraderResponse::of).toList();
    }

    @PostMapping("/api/v1/councils/{councilId}/agents")
    ResponseEntity<AgentResponse> registerAgent(
            @PathVariable UUID councilId, @RequestBody RegisterAgentRequest request) {
        Agent agent = service.registerAgent(councilId, request.phone(), request.displayName());
        return ResponseEntity.created(URI.create("/api/v1/councils/" + councilId + "/agents/" + agent.id()))
                .body(AgentResponse.of(agent));
    }

    @GetMapping("/api/v1/councils/{councilId}/agents")
    List<AgentResponse> listAgents(@PathVariable UUID councilId) {
        return service.listAgents(councilId).stream().map(AgentResponse::of).toList();
    }

    /** All of a market's stall plates as one printable PDF, one A6 page per stall. */
    @GetMapping("/api/v1/councils/{councilId}/markets/{marketId}/plates.pdf")
    ResponseEntity<byte[]> plates(@PathVariable UUID councilId, @PathVariable UUID marketId) {
        byte[] pdf = service.plates(councilId, marketId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("stall-plates.pdf").build().toString())
                .body(pdf);
    }
}
