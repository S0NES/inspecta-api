# inspecta-api

API Gateway do **Inspecta** — plataforma de vistoria imobiliaria. Java 21 + Spring Boot 3.

## O que este projeto faz

Expoe a API REST publica consumida pelo frontend/app do Inspecta:

- **Autenticacao e autorizacao**: login JWT, API keys, RBAC via `permissions.yml`, RLS multi-tenant.
- **Cadastros**: usuarios, imobiliarias, vistoriadores, contratos, disponibilidade.
- **Fluxo de vistoria**: solicitacoes, atribuicao (assignment), laudos, upload de arquivos/fotos, contestacao, assinatura (Autentique).
- **Financeiro**: pagamentos, repasse a vistoriador, relatorios.
- **Webhooks** recebidos de provedores externos.
- **Leitura on-demand da Pleno**: unica chamada HTTP externa sincrona do servico (timeout 3s + circuit breaker + cache Postgres).

Regra de arquitetura: este servico **nao tem entity/repository proprios**. Todo CRUD (salvar/editar/buscar) e delegado, in-process, para a biblioteca [inspecta-core](../inspecta-core). Processamento pesado (PDF, imagem, integracoes, pagamento) **nao roda aqui** — o inspecta-api publica um evento via outbox e quem processa e o [inspecta-worker](../inspecta-worker). Mensageria (email/sms/whatsapp/push) tambem nao roda aqui — publica `notify.requested.*` e quem envia e o [inspecta-messenger](../inspecta-messenger).

E o **unico** dos servicos que executa as migrations Flyway no boot (as migrations moram no jar do inspecta-core).

## Stack

Java 21, Spring Boot 3.5, Spring Security + JWT (jjwt), Spring Data JPA (via inspecta-core), Flyway, RabbitMQ (outbox), Postgres (file storage), Resilience4j, springdoc-openapi. Pacote base: `br.com.vistoria.core`; classe principal: `ApiApplication`.

## Como rodar

### 1. Subir a infra local (na raiz do workspace `Inspecta/`)
```
docker compose up -d
```
Isso sobe Postgres (5432) e RabbitMQ (5672 / management 15672).

### 2. Instalar a dependencia inspecta-core no repositorio Maven local
```
cd ../inspecta-core
mvn install
```

### 3. Rodar o inspecta-api
```
cd ../inspecta-api
mvn spring-boot:run
```
Por padrao sobe com o profile `test` (infra local, sem credenciais), na porta **8080**.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Health check: http://localhost:8080/actuator/health

### Seeds do banco (V1__baseline.sql)

A migration única semeia, de forma idempotente:

| Seed | O que é |
|---|---|
| **Admin bootstrap** | `usuarios` com `perfil='admin'`, `status='ativo'`, e-mail `xena.w@example.org`. **`senha_hash` nasce NULL** — antes de logar, gere um hash BCrypt (o `BCryptPasswordEncoder` de `SecurityConfig`, custo 10) e rode `UPDATE usuarios SET senha_hash='<hash>' WHERE email='xena.w@example.org';`. Não é segredo de produção — só destrava o primeiro acesso em dev. |
| **Cliente "Inspecta - Avulso"** | `clientes` com UUID fixo `11111111-1111-4111-8111-111111111111`, `is_global_client=true`. É a parte comercial de todo pedido "avulso": uma solicitação normal cujo `cliente_id` é esse. O flag (não o nome) é que permite amarrar qualquer imóvel. Nenhuma API escreve `is_global_client`. |
| **Papéis de interveniente** | `interveniente_papeis`: `locador`, `locatario`, `fiador` (sem `testemunha`). Lookup editável só pelo banco. |
| **Faixas de referência** | as 5 faixas globais de metragem. |
| `notify.template_utilidades` | os 7 vínculos utilidade → template. |

### Docker (imagem de producao)
O build precisa enxergar o `inspecta-core` (mesmo contexto pai), entao o build e feito a partir da **raiz do workspace**, nao desta pasta:
```
cd ..
docker build -f inspecta-api/Dockerfile -t inspecta-api .
```

### Variaveis de ambiente

#### Local (profile `test`, padrão)

O arquivo `.env` é carregado automaticamente pelo profile `test` via `spring.config.import: optional:file:.env[.properties]`. Copie `.env.example` para `.env` e preencha os valores.

**Nunca commitado:** `.env` está em `.gitignore`. Credenciais reais nunca entram no working tree.

#### Producao (profile `prod`)

**Infra obrigatoria (fail-fast, boot falha se nao setada):**

| Variavel | Descricao | Origem |
|---|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | Postgres | Deploy secrets |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | RabbitMQ | Deploy secrets |
| `JWT_SECRET` | segredo de assinatura dos tokens (min. 256 bits) | Deploy secrets |
| `PAYMENT_PROVIDER` | provider de pagamento ativo; necessario em prod para ambos inspecta-api e inspecta-worker (fail-fast, nao tem default). API publica eventos; inspecta-worker consome e cobra. | Deploy secrets |
| `PLENO_MODE`, `PLENO_BASE_URL`, `PLENO_CLIENT_ID`, `PLENO_CLIENT_SECRET` | integracao Pleno (fail-fast, todas obrigatorias) | Deploy secrets |

**Opcionais:**

| Variavel | Descricao | Default |
|---|---|---|
| `SIGNATURE_BROKER` | broker de assinatura ativo (`mock` \| `autentique`), mesmo valor setado no inspecta-messenger. O inspecta-api nao instancia broker nenhum — so reporta o nome em `GET /v1/integracoes/config` para o painel de integracoes escolher qual aba de assinatura renderizar. | `mock` |

**Onde vem as secrets em prod:** Variáveis de ambiente são configuradas no sistema de deploy (Kubernetes secrets, Cloud provider env vars, etc.), nunca em `.env` — o arquivo `.env` é apenas para desenvolvimento local. Ver `backend-specs/arquitetura.md` § Build local para maiores detalhes e T7.3 para o fluxo completo de deploy.

Ver `backend-specs/arquitetura.md` para as regras invariantes do sistema.

## Licenca

Software proprietario. Uso comercial sujeito a licenca — ver [LICENSE](./LICENSE).
