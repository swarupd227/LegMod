// Neo4j constraints and indexes for the Atlas knowledge graph.
// Run once after first boot: docker compose exec neo4j cypher-shell -u neo4j -p atlas_dev_pwd -f /init.cypher

CREATE CONSTRAINT project_id IF NOT EXISTS
  FOR (p:Project) REQUIRE p.id IS UNIQUE;

CREATE CONSTRAINT operation_key IF NOT EXISTS
  FOR (o:Operation) REQUIRE (o.project_id, o.namespace, o.name) IS UNIQUE;

CREATE CONSTRAINT type_key IF NOT EXISTS
  FOR (t:Type) REQUIRE (t.project_id, t.namespace, t.name) IS UNIQUE;

CREATE CONSTRAINT class_fqn IF NOT EXISTS
  FOR (c:Class) REQUIRE (c.project_id, c.fqn) IS UNIQUE;

CREATE CONSTRAINT adapter_fqn IF NOT EXISTS
  FOR (a:Adapter) REQUIRE (a.project_id, a.fqn) IS UNIQUE;

CREATE INDEX op_name IF NOT EXISTS FOR (o:Operation) ON (o.name);
CREATE INDEX type_name IF NOT EXISTS FOR (t:Type) ON (t.name);
