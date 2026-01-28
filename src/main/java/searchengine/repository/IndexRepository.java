package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;

import java.util.List;

public interface IndexRepository extends JpaRepository<IndexEntity, Integer> {
    List<IndexEntity> findByLemma(LemmaEntity lemma);
    List<IndexEntity> findByLemmaIn(List<LemmaEntity> lemmas);
    List<IndexEntity> findByPage(PageEntity page);
    List<IndexEntity> findByPageInAndLemmaIn(List<PageEntity> pages, List<LemmaEntity> lemmas);
    List<IndexEntity> findByPageAndLemmaIn(PageEntity page, List<LemmaEntity> lemmas);
    boolean existsByPageAndLemma(PageEntity page, LemmaEntity lemma);

    @Modifying
    @Transactional
    @Query(value = "delete i from `index` i join page p on i.page_id = p.id where p.site_id = :siteId", nativeQuery = true)
    void deleteBySiteId(@Param("siteId") int siteId);
}
