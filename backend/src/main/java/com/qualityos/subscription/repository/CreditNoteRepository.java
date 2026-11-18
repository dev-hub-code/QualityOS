package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.CreditNote;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditNoteRepository extends JpaRepository<CreditNote, UUID> {

    List<CreditNote> findByInvoiceId(UUID invoiceId);

    List<CreditNote> findAllByOrderByCreatedAtDesc();
}
