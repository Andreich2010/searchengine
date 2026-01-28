package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.SiteEntity;
import searchengine.model.PageEntity;

import java.util.List;
import java.util.Optional;

public interface PageRepository extends JpaRepository<PageEntity, Integer> {
    Optional<PageEntity> findByPathAndSite(String path, SiteEntity site);
    boolean existsByPathAndSite(String path, SiteEntity site);
    long countBySite(SiteEntity site);
    List<PageEntity> findBySite(SiteEntity site);

    @Modifying
    @Transactional
    @Query(value = "delete from page where site_id = :siteId", nativeQuery = true)
    void deleteBySiteId(@Param("siteId") int siteId);
}
