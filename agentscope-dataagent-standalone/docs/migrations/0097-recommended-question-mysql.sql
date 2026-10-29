-- spec098: the default question the query page prefills for a knowledge base.
-- Generated in the background after each publish; null until generated.
-- Hibernate ddl-auto=update adds this column automatically; run manually where it is disabled.
ALTER TABLE dataagent_dataset_group ADD COLUMN recommended_question VARCHAR(500) NULL;
-- True once the modeler edited the question in the modeling trial; generation never overwrites it.
ALTER TABLE dataagent_dataset_group ADD COLUMN recommended_question_manual BIT(1) NULL;
