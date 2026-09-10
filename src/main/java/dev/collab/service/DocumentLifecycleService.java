package dev.collab.service;

import dev.collab.api.ApiException;
import dev.collab.service.CollaborationModels.*;
import dev.collab.store.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentLifecycleService {
  private final DocumentRepository documents;
  private final UserRepository users;
  private final JdbcTemplate db;
  private final DocumentAccess access;
  private final ApplicationEventPublisher events;

  public DocumentLifecycleService(
      DocumentRepository documents,
      UserRepository users,
      JdbcTemplate db,
      DocumentAccess access,
      ApplicationEventPublisher events) {
    this.documents = documents;
    this.users = users;
    this.db = db;
    this.access = access;
    this.events = events;
  }

  @Transactional
  public DocumentView create(UUID userId, String title) {
    db.queryForObject("select id from app_users where id=? for update", UUID.class, userId);
    if (db.queryForObject(
            "select count(*) from documents where owner_id=? and not deleted", Long.class, userId)
        >= 100) throw ApiException.conflict("DOCUMENT_LIMIT");
    var doc = documents.saveAndFlush(new DocumentEntity(UUID.randomUUID(), userId, title.trim()));
    return view(doc, Role.OWNER);
  }

  @Transactional(readOnly = true)
  public List<DocumentView> list(UUID userId, int offset, int limit) {
    if (offset < 0 || offset > 10000 || limit < 1 || limit > 100)
      throw new IllegalArgumentException("Invalid page");
    return db.query(
        "select d.*,case when d.owner_id=? then 'OWNER' else m.role end as access_role from"
            + " documents d left join document_members m on m.document_id=d.id and m.user_id=?"
            + " where not d.deleted and (d.owner_id=? or m.user_id is not null) order by"
            + " d.created_at desc,d.id limit ? offset ?",
        (rs, row) ->
            new DocumentView(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                Role.valueOf(rs.getString("access_role")),
                rs.getLong("last_sequence"),
                rs.getTimestamp("updated_at").toInstant()),
        userId,
        userId,
        userId,
        limit,
        offset);
  }

  @Transactional(readOnly = true)
  public DocumentView get(UUID docId, UUID userId) {
    var doc = access.find(docId, false);
    return view(doc, access.role(doc, userId));
  }

  @Transactional
  public void delete(UUID docId, UUID userId) {
    var doc = access.find(docId, true);
    access.owner(doc, userId);
    doc.delete();
    events.publishEvent(new Changed(docId, doc.lastSequence(), 0, 0, 0));
  }

  @Transactional(readOnly = true)
  public List<Member> members(UUID docId, UUID userId) {
    var doc = access.find(docId, false);
    access.role(doc, userId);
    return db.query(
        "select u.id,u.email,'OWNER' as role from app_users u where u.id=? union all select"
            + " u.id,u.email,m.role from document_members m join app_users u on u.id=m.user_id"
            + " where m.document_id=? order by email",
        (rs, row) ->
            new Member(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                Role.valueOf(rs.getString("role"))),
        doc.ownerId(),
        docId);
  }

  @Transactional
  public void share(UUID docId, UUID userId, String email, Role role) {
    var doc = access.find(docId, true);
    access.owner(doc, userId);
    if (role == null || role == Role.OWNER)
      throw new IllegalArgumentException("Role must be VIEWER or EDITOR");
    var member =
        users
            .findByEmail(email.trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    if (member.id().equals(doc.ownerId())) throw ApiException.conflict("OWNER_IMMUTABLE");
    if (db.queryForObject(
                "select count(*) from document_members where document_id=?", Long.class, docId)
            >= 100
        && db.queryForObject(
                "select count(*) from document_members where document_id=? and user_id=?",
                Long.class,
                docId,
                member.id())
            == 0) throw ApiException.conflict("MEMBER_LIMIT");
    db.update(
        "insert into document_members(document_id,user_id,role) values(?,?,?) on"
            + " conflict(document_id,user_id) do update set role=excluded.role",
        docId,
        member.id(),
        role.name());
    events.publishEvent(new Changed(docId, doc.lastSequence(), 0, 0, 0));
  }

  @Transactional
  public void removeMember(UUID docId, UUID userId, UUID memberId) {
    var doc = access.find(docId, true);
    access.owner(doc, userId);
    if (memberId.equals(doc.ownerId())) throw ApiException.conflict("OWNER_IMMUTABLE");
    db.update("delete from document_members where document_id=? and user_id=?", docId, memberId);
    events.publishEvent(new Changed(docId, doc.lastSequence(), 0, 0, 0));
  }

  private DocumentView view(DocumentEntity doc, Role role) {
    return new DocumentView(doc.id(), doc.title(), role, doc.lastSequence(), doc.updatedAt());
  }
}
