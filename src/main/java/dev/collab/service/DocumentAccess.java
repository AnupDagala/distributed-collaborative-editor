package dev.collab.service;

import dev.collab.api.ApiException;
import dev.collab.service.CollaborationModels.*;
import dev.collab.store.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Uses the caller's transaction, ensuring every mutation shares document-first lock order. */
@Service
public class DocumentAccess {
  private final DocumentRepository documents;
  private final JdbcTemplate db;

  public DocumentAccess(DocumentRepository documents, JdbcTemplate db) {
    this.documents = documents;
    this.db = db;
  }

  public DocumentEntity find(UUID docId, boolean lock) {
    var doc =
        (lock ? documents.lock(docId) : documents.findById(docId))
            .orElseThrow(ApiException::missing);
    if (doc.deleted()) throw ApiException.missing();
    return doc;
  }

  public Role role(DocumentEntity doc, UUID userId) {
    if (doc.ownerId().equals(userId)) return Role.OWNER;
    var roles =
        db.queryForList(
            "select role from document_members where document_id=? and user_id=?",
            String.class,
            doc.id(),
            userId);
    if (roles.isEmpty()) throw ApiException.forbidden();
    return Role.valueOf(roles.getFirst());
  }

  public void owner(DocumentEntity doc, UUID userId) {
    if (role(doc, userId) != Role.OWNER) throw ApiException.forbidden();
  }
}
