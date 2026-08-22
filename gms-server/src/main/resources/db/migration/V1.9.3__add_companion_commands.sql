-- 注册同账号随从与热切换指令
INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`) VALUES ('companion', 0, 1, 'CompanionCommand', 0);
INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`) VALUES ('switch', 0, 1, 'CompanionSwitchCommand', 0);
INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`) VALUES ('dismiss', 0, 1, 'CompanionDismissCommand', 0);
