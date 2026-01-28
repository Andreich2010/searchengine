CREATE TABLE IF NOT EXISTS site (
  id INT NOT NULL AUTO_INCREMENT,
  status ENUM('INDEXING','INDEXED','FAILED') NOT NULL,
  status_time DATETIME NOT NULL,
  last_error TEXT,
  url VARCHAR(255) NOT NULL,
  name VARCHAR(255) NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS page (
  id INT NOT NULL AUTO_INCREMENT,
  site_id INT NOT NULL,
  path TEXT NOT NULL,
  code INT NOT NULL,
  content MEDIUMTEXT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_page_path (path(255)),
  CONSTRAINT fk_page_site FOREIGN KEY (site_id) REFERENCES site(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS lemma (
  id INT NOT NULL AUTO_INCREMENT,
  site_id INT NOT NULL,
  lemma VARCHAR(255) NOT NULL,
  frequency INT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_lemma_site (site_id, lemma),
  CONSTRAINT fk_lemma_site FOREIGN KEY (site_id) REFERENCES site(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `index` (
  id INT NOT NULL AUTO_INCREMENT,
  page_id INT NOT NULL,
  lemma_id INT NOT NULL,
  `rank` FLOAT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_index_page_lemma (page_id, lemma_id),
  CONSTRAINT fk_index_page FOREIGN KEY (page_id) REFERENCES page(id) ON DELETE CASCADE,
  CONSTRAINT fk_index_lemma FOREIGN KEY (lemma_id) REFERENCES lemma(id) ON DELETE CASCADE
) ENGINE=InnoDB;
