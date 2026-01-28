package searchengine.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "lemma",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_lemma_site", columnNames = { "site_id", "lemma" })
        },
        indexes = {
                @Index(name = "idx_lemma_value", columnList = "lemma")
        }
)
public class LemmaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private SiteEntity site;

    @Column(nullable = false)
    private String lemma;

    @Column(nullable = false)
    private int frequency;
}
