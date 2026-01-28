package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.SiteEntity;
import searchengine.model.LemmaEntity;

import java.util.List;
import java.util.Optional;

public interface LemmaRepository extends JpaRepository<LemmaEntity, Integer> {
    Optional<LemmaEntity> findByLemmaAndSite(String lemma, SiteEntity site);
    List<LemmaEntity> findByLemmaInAndSite(List<String> lemmas, SiteEntity site);
    List<LemmaEntity> findByLemmaIn(List<String> lemmas);
    long countBySite(SiteEntity site);

    @Modifying
    @Transactional
    @Query(value = "delete from lemma where site_id = :siteId", nativeQuery = true)
    void deleteBySiteId(@Param("siteId") int siteId);

    @Modifying
    @Transactional
    @Query("update LemmaEntity l set l.frequency = l.frequency + 1 where l.site = :site and l.lemma = :lemma")
    int incrementFrequency(@Param("site") SiteEntity site, @Param("lemma") String lemma);
}
