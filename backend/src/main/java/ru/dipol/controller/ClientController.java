package ru.dipol.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.dipol.dto.PageResponse;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.model.Client;
import ru.dipol.model.ClientContact;
import ru.dipol.repository.ClientRepository;
import ru.dipol.service.AuditService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientRepository repo;
    private final AuditService audit;

    public ClientController(ClientRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public record ClientRequest(
            @NotBlank String clientType,
            @NotBlank String name,
            String shortName, String inn, String kpp, String ogrn,
            String legalAddress, String actualAddress,
            String bankName, String bankBik, String bankAccount, String corrAccount,
            String phone, String email, String comment, Boolean isActive
    ) {
        Client toModel() {
            return new Client(null, clientType, name, shortName, inn, kpp, ogrn, legalAddress,
                    actualAddress, bankName, bankBik, bankAccount, corrAccount, phone, email,
                    comment, isActive == null || isActive, null);
        }
    }

    public record ContactRequest(
            @NotBlank String fullName, String position, String contactRole,
            String phone, String email, String messenger, Long zheuId,
            String comment, Boolean isActive
    ) {
        ClientContact toModel(Long clientId) {
            return new ClientContact(null, clientId, fullName, position, contactRole, phone,
                    email, messenger, zheuId, comment, isActive == null || isActive);
        }
    }

    @GetMapping
    public PageResponse<Client> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(repo.search(q, type, includeInactive, size, page * size),
                repo.count(q, type, includeInactive), page, size);
    }

    @GetMapping("/{id}")
    public Client get(@PathVariable Long id) {
        return repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Клиент", id));
    }

    @GetMapping("/{id}/summary")
    public Map<String, Object> summary(@PathVariable Long id) {
        return repo.summary(id);
    }

    @GetMapping("/{id}/projects")
    public List<Map<String, Object>> projects(@PathVariable Long id) {
        return repo.projects(id);
    }

    @PostMapping
    public Client create(@Valid @RequestBody ClientRequest req) {
        Client c = repo.create(req.toModel());
        audit.created("CLIENT", c.id());
        return c;
    }

    @PutMapping("/{id}")
    public Client update(@PathVariable Long id, @Valid @RequestBody ClientRequest req) {
        Client before = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Клиент", id));
        Client after = repo.update(id, req.toModel());
        audit.changed("CLIENT", id, "name", before.name(), after.name());
        audit.changed("CLIENT", id, "inn", before.inn(), after.inn());
        audit.changed("CLIENT", id, "is_active", before.isActive(), after.isActive());
        return after;
    }

    @GetMapping("/{id}/contacts")
    public List<ClientContact> contacts(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean includeInactive) {
        return repo.contacts(id, includeInactive);
    }

    @PostMapping("/{id}/contacts")
    public ClientContact addContact(@PathVariable Long id, @Valid @RequestBody ContactRequest req) {
        ClientContact c = repo.createContact(req.toModel(id));
        audit.action("CLIENT", id, "CONTACT_ADD", req.fullName());
        return c;
    }

    @PutMapping("/contacts/{contactId}")
    public ClientContact updateContact(@PathVariable Long contactId, @Valid @RequestBody ContactRequest req) {
        ClientContact existing = repo.contact(contactId)
                .orElseThrow(() -> new EntityNotFoundException("Контактное лицо", contactId));
        ClientContact updated = repo.updateContact(contactId, req.toModel(existing.clientId()));
        audit.action("CLIENT", existing.clientId(), "CONTACT_UPDATE", req.fullName());
        return updated;
    }
}
