CREATE TABLE IF NOT EXISTS `character_visited_maps` (
    `character_id` INT NOT NULL,
    `map_id` INT NOT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`character_id`, `map_id`),
    INDEX `idx_char_visited_map` (`character_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
