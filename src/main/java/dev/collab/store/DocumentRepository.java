package dev.collab.store;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from DocumentEntity d where d.id=:id")
  Optional<DocumentEntity> lock(@Param("id") UUID id);
}
