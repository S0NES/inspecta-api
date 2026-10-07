-- Fixture exclusiva do build-runner (Dokploy): cria um admin de login pra rodar a
-- pasta "Auth" da collection Postman/Bruno contra o stack efêmero (Postgres recém-
-- criado do zero) durante o pipeline de build+teste. NUNCA aplicada em prod: só
-- entra no classpath quando spring.flyway.locations inclui db/migration-ci, o que só
-- acontece com o profile "ci" (ver application-ci.yml, usado exclusivamente por
-- ci/entrypoint.sh). O profile "prod" usa só db/migration.
--
-- senha em texto puro: ci-test-only-9f3a (hash BCrypt abaixo) — sem risco real, o
-- banco onde isso roda é descartado ao fim de cada execução do build-runner.
INSERT INTO usuarios (nome, email, senha_hash, perfil, status)
VALUES (
    'CI Test Admin',
    'ci-admin@inspecta.test',
    '$2b$10$tVZobTeQVZ9ixM4l5Hbgz.DLb8GtyOmRVBBRzLM2Nwg/cpF8CxLWu',
    'admin',
    'ativo'
);
