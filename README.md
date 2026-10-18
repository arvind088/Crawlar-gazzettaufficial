# Italian Legislation Linked Data

Master's thesis project. It turns Italian national legislation into **linked open data**:
every act gets a permanent [ELI](https://op.europa.eu/en/web/eu-vocabularies/eli) address,
all its versions, the relations to other acts (amends, repeals, converts) with their evidence,
and a public SPARQL endpoint.

**Corpus:** every *Legge*, *Decreto-legge* and *Decreto legislativo* from 2020 to today.

| | |
|---|---|
| Acts | 1,116 |
| Versions (original and consolidated texts) | 4,212 |
| Acts confirmed against Gazzetta Ufficiale | 1,114 (2 server errors, retried by the next update) |
| Relations between acts | 7,854 (amends 6,079 · changes 1,551 · repeals 224) |
| Decree-laws with a conversion status | 249 |
| Precision, hand-checked random sample | 54 / 54 correct (≥ 93.4 %, 95 % Wilson interval) |

*Figures of 11 October 2026; they grow with each daily update.*

## How it works

```mermaid
flowchart LR
    N[Normattiva<br/>OpenData API] -->|Akoma Ntoso XML,<br/>all versions| I[Import<br/>XML → ELI RDF]
    I --> G[Check each act on<br/>Gazzetta Ufficiale]
    I --> R[Find relations<br/>amends · repeals · converts]
    G --> S[(Jena TDB2<br/>triple store)]
    R --> S
    I --> S
    S --> W[Web pages<br/>Legal acts · Act details]
    S --> Q[SPARQL<br/>/query · /sparql]
```

Details: [ARCHITECTURE.md](ARCHITECTURE.md). External sources and the app's own endpoints:
[docs/API.md](docs/API.md).

## Quick start

Requirements: **Java 17** and **Maven 3.9+**.

```bash
cd jena-git-project
mvn -B test            # build and run the tests
mvn spring-boot:run    # start the app
```

Open <http://localhost:8080>. The first start reads `data/rdf/*.ttl` into the store
(`data/tdb2`), which takes a few minutes; later starts are fast.

| View | Address |
|---|---|
| Legal acts (search, filters) | `/` |
| Act details (versions, Gazzetta check, conversion, relations) | `/eli/id/{yyyy}/{mm}/{dd}/{code}/sg`, e.g. `/eli/id/2020/03/17/20G00034/sg` |
| SPARQL form and example queries | `/query` |
| SPARQL 1.1 endpoint for programs | `/sparql` |
| Health | `/api/health` |

Each act page is also RDF: `curl -H "Accept: text/turtle" http://localhost:8080/eli/id/2020/03/17/20G00034/sg`.

## Keeping the data up to date

The committed RDF files are enough to run the app. To fetch new and changed acts:

```bash
scripts/daily-update.sh --dry-run true   # what changed, nothing downloaded
scripts/daily-update.sh                  # download, import, check, relations (about 15–40 min)
```

On Windows use the `.cmd` scripts (`scripts\daily-update.cmd`). The update needs the raw
Normattiva corpus on disk (`scripts/build-corpus.sh` builds it once; 2.6 GB, not in Git) and a
network Normattiva accepts: it blocks GitHub's servers, so it runs on your own machine or server.

All helper scripts are in [`scripts/`](scripts):

| Script | Does |
|---|---|
| `run-app` | Start the web app |
| `build-corpus` | First full download and processing (hours) |
| `daily-update` | Fetch changed and missing acts, re-run the pipeline |
| `evaluate` | Checks by hand: precision (`sample`, then `evaluate`) and recall (`recall-sample`, then `recall`) |
| `validate-akn` | Check Akoma Ntoso files before import |

## Repository layout

```text
README.md, ARCHITECTURE.md, CONTRIBUTING.md, LICENSE
docs/API.md                 External APIs (Normattiva, Gazzetta) and the app's endpoints
scripts/                    Helper scripts (.sh for Linux/Git Bash, .cmd for Windows)
.github/workflows/          CI (tests on pull requests) and the scheduled Gazzetta RSS harvest
render.yaml                 Deployment definition (Render)
jena-git-project/           The application (Java 17, Spring Boot, Apache Jena)
  src/main/java/it/legislation/
    source/                 Clients for Normattiva and Gazzetta Ufficiale
    mapping/                Akoma Ntoso → ELI RDF
    relations/              Relation and conversion detection
    ingest/                 Runnable steps (import, check, relations, daily update, evaluation)
    store/                  TDB2 triple store
    eli/, web/              ELI addresses, pages, SPARQL
  data/                     Outputs in Git: RDF, readable TSV reports, registry, evaluation
  queries/                  The example questions on /query; more/ holds further queries
```

## Data sources and licence

- Legislation: [Normattiva](https://www.normattiva.it) OpenData (Istituto Poligrafico e Zecca dello
  Stato), used under its terms of use. Publication data: [Gazzetta Ufficiale](https://www.gazzettaufficiale.it).
- Code: [MIT licence](LICENSE).
