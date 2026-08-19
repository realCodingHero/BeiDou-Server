CREATE TABLE IF NOT EXISTS `account_mob_kills` (
  `account_id` int NOT NULL,
  `mob_id` int NOT NULL,
  `kill_count` bigint NOT NULL DEFAULT '0',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`account_id`, `mob_id`),
  KEY `idx_account_id` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
