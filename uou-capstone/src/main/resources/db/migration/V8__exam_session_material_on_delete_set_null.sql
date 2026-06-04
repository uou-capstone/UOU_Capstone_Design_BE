ALTER TABLE `exam_sessions`
    DROP FOREIGN KEY `FKwb32bt53e2v9ogoogjfa006g`;

ALTER TABLE `exam_sessions`
    ADD CONSTRAINT `FKwb32bt53e2v9ogoogjfa006g`
        FOREIGN KEY (`material_id`)
        REFERENCES `material` (`material_id`)
        ON DELETE SET NULL;
