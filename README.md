# regtests-prototype

Regression tests for one Java service, with no shared test environment.

Testing a service usually means standing up everything it talks to: a database with the right
rows, partners that answer, other teams' services that happen to be working today. This repo shows
another way. **Test one service at a time and cut it off at its edge.** Record its traffic once.
Then run every new build against that recording, with no database and no network. The service's
own code runs for real. Everything it calls is answered from a file.

```
                 RECORD (once)                               REPLAY (every build)

 request --> [ service code ] --> real database      test --> [ service code ] --> recording
                    |         --> real partner                       |         --> recording
                    v                                                v
              saved to files                              compared with those files
```

Record runs once, in UAT. Replay needs no environment, so it's cheap enough to run on every build:
in CI, in CD, or after rehydration.

You can watch all of this happen, live, on a page in your browser: a diagram of who is calling
whom, and the code behind each step.

## What you need

Docker. That's all: no Java, Maven or Python on your machine. The first build downloads images and
libraries, so it needs a network connection. Allow about 3 GB of disk.

## Run it

From the repo folder:

```
docker build -t regtests-prototype .
```

The first build takes about a minute on a fast connection, longer on a slow one. It compiles
everything and runs the Java and Python unit tests. A failing test fails the build. You should finish with `naming to docker.io/library/regtests-prototype`.

```
docker run --rm -p 8080:8080 regtests-prototype
```

After about ten seconds the terminal says `Ready. Open http://localhost:8080 and press Run or Next.`

Open <http://localhost:8080>. You should see three stages, and a list of processes that are all
RUNNING. Press **Run** to play it through at about one step a second, or **Next** for one step per
press. **Restart** starts again from a clean database. The same steps print in the terminal.
Press Ctrl+C in the terminal to stop.

Everything on the page comes from the real run. The run goes at full speed, so its events are held
and shown one at a time, each stamped with the time it happened. Stops and restarts of processes
happen for real when their step comes up.

## The three stages

**1. Record.** MariaDB and a fake partner service are running, each as its own process. One real request
arrives at `POST /quotes`. You see it get a request id, then each database call and partner call
captured under that id. Personal data is masked. The response goes back and the case is saved to
disk.

Each database step carries a label, *stored procedure via MyBatis* or *plain JDBC*. The service
uses both in the same request, and both are captured by the same hook.

**2. Replay.** The database and the partner are stopped, and the page shows them STOPPED. The
service starts anyway, because a fake `DataSource` replaces the real one and never connects. A
Python test sends the recorded request. Every database call and partner call is answered from the
recording, and the library writes down what the service did. It makes no judgement. The Python
runner reads that back and compares the response and every attempted call with the recording. PASS.

**3. Break it.** The service restarts with `DEMO_BREAK=true`, which flips one line of pricing. The
same test now fails, and the page shows exactly what changed:

```
Database call create_quote parameter 6: expected 764.35, got 928.65
Database call INSERT INTO audit_log parameter 3: expected "Premium 764.35", got "Premium 928.65"
Response $.premium: expected 764.35, got 928.65
```

Nobody wrote an assertion about the premium. The recording holds every expected value, so the
recording is the test. When a case fails, there are two choices:

- **A bug:** fix the code.
- **An intended change:** update the expected values. They're in the recording, so record the case
  again, or edit them by hand: the response in `inbound.json`, and the values sent in `sql.json`
  and `outbound-http.json`. Either way, that becomes the new baseline.

## What a team has to do

Add one dependency and turn it on with one setting. That's all. `demo-service` in this repo proves
it: it has no line of code that knows about any of this, and a build check
(`NoCaptureCodeTest`) fails if one appears.

```xml
<dependency>
  <groupId>example.regtests</groupId>
  <artifactId>regtests-capture-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```
REGTESTS_MODE=off      the default, and production: nothing is created, nothing changes
REGTESTS_MODE=record   in UAT: capture real traffic into files
REGTESTS_MODE=replay   in CI, CD or after rehydration: answer everything from those files
```

Spring Boot auto-configuration does the wiring. The only assumption is Spring Boot 3. How a team
reaches its database and other services can vary.

**The database needs nothing, whatever sits on top.** The hook is on the `DataSource`, at the JDBC
level. MyBatis, `JdbcTemplate` and JPA all end up calling JDBC, so one hook covers them all, and
the library doesn't depend on any of them. In this repo one request uses MyBatis with stored
procedures and also plain SQL through `JdbcTemplate`, and both go through the same wrapper. The
library's tests also record and replay JPA. See [Known limits](#known-limits) for the JPA settings.

**HTTP depends on how the client was built:**

| Client | What's needed |
|---|---|
| `RestTemplate` from `RestTemplateBuilder` | Nothing |
| `RestTemplate` declared as a bean, even with `new` | Nothing |
| `RestClient` from the injected `RestClient.Builder` | Nothing |
| `WebClient` from the injected `WebClient.Builder` | Nothing |
| `new RestTemplate()` inside a method | Use the injected builder: `restTemplateBuilder.build()` |
| `RestClient.create()` or `WebClient.create()` inside a method | Use the injected builder |
| OkHttp | One line where you build it: `.addInterceptor(RegtestsOkHttp.interceptor())` |
| Apache HttpClient 5 | One line where you build it: `.addExecInterceptorFirst("regtests", RegtestsApacheHttp.interceptor())` |
| Anything else (Feign, `HttpURLConnection`, the JDK `HttpClient`) | Not hooked |

The OkHttp and Apache dependencies are optional, so a team that doesn't use them pulls nothing in.
With `REGTESTS_MODE=off` those two lines pass every call straight through.

**How a team finds out which line it needs:** run replay with no network. An unhooked client then
can't reach anything, so the failure names the host it tried:

```
docker run --rm -e DEMO_UNHOOKED_CLIENT=true regtests-prototype test
```

That setting makes `demo-service` use a `new RestTemplate()` inside a method for one call. The case
fails like this:

```
Recorded but never made: GET /fees/home
The service failed with: ... I/O error on GET request for "http://localhost:8082/fees/home": Connection refused
```

In this demo the partner being down guarantees that. Wherever you run replay, in CI, CD or after
rehydration, run it with no network, for example `docker run --network none`. Otherwise an unhooked client could reach a real host and pass
without anyone noticing.

Three things are optional. `REGTESTS_RECORDINGS` sets the recordings folder (default `recordings`).
`REGTESTS_ACTUAL` sets where replay writes down what the service did (default `actual`). A
`regtests.json` file in the recordings folder tunes the comparison and the masking, with no code. See
[What may differ between runs](#what-may-differ-between-runs).

## Run the tests without the page

This replays the recordings committed in `recordings/`. Only the service runs, with no database, no
partner, and here no network at all:

```
docker run --rm --network none regtests-prototype test
```

You should see `21 passed`: 14 tests of the comparison rules, the four recorded cases, a check that
the database and partner are down, a check that an unexpected call fails loudly, and a check that a
request naming no case is refused.

To see which cases a regression touches:

```
docker run --rm -e DEMO_BREAK=true regtests-prototype test
```

Now `2 failed, 19 passed`. The gold and silver customers get a discount, so the changed rule affects
them. The standard customer and the unknown customer don't, and they still pass.

To record the example cases again from a real run:

```
docker run --rm -v "$(pwd)/recordings:/data/recordings" regtests-prototype record-examples
```

## How recordings are laid out

One folder per case, three JSON files each, plus one config file for the service:

```
recordings/
  regtests.json              what may differ, and what is personal (optional)
  gold-home-quote/
    inbound.json             the request that came in and the response that went back
    outbound-http.json       every partner call: URL, body sent, answer received
    sql.json                 every database call: parameters, OUT values, rows, counts
  silver-car-quote/
  standard-travel-quote/
  unknown-customer/

actual/
  3f9a1c2e/                  one replay run, named by its run id, with the same three files
```

A recording is what the service did once. A run in `actual/` is what the new build did this time.
They have the same format, so comparing them needs nothing from the library. Runs aren't committed.

### The format

Every file is UTF-8 JSON, and personal data is masked before it is written.

**`inbound.json`** is one object:

| Field | Meaning |
|---|---|
| `case` | The case name |
| `requestId` | The id given to this request. In a run, this is the run id |
| `recordedAt` | When it was written, as an ISO timestamp |
| `request` | `method`, `path`, `query` (left out if there is none), `contentType` and `body` |
| `response` | `status`, `contentType` and `body` |
| `error` | In a run only, and only if the service threw: the error message |

**`outbound-http.json`** is an array with one object per call:

| Field | Meaning |
|---|---|
| `seq` | The call's place in the request, counted across this file and `sql.json`, from 1 |
| `thread` | Where it ran. For reading only |
| `method`, `url` | The call, with its full URL and query |
| `requestBody` | The body sent |
| `status`, `contentType`, `responseBody` | The answer |

**`sql.json`** is an array with one object per database call, whether plain SQL or a procedure:

| Field | Meaning |
|---|---|
| `seq`, `thread` | As above |
| `sql` | The statement, with each run of whitespace turned into one space |
| `params` | IN values by position. An OUT position holds null, and trailing ones may be left off |
| `out` | One `{position, value, javaType}` for each OUT parameter |
| `results` | Everything the call returned, in order. Each is a result set, `{columns, rows}`, or `{updateCount}` |

Three rules apply to values:

- A `body` is parsed JSON if the text is JSON, a string if it isn't, and null or left out if empty.
- Numbers keep their exact decimal value. Dates and times are ISO text.
- `columns` is a list of `{name, javaType, sqlType}`, and `rows` a list of arrays in column order.

Two things are special in a run. A call the recording could not answer is still listed, with
`status` 0 for HTTP, or empty `out` and `results` for a database call. And `error` may be present.

The Python runner reads only `case`, `response`, `error`, `seq`, `method`, `url`, `requestBody`, `sql`
and `params`. The rest is there for the library, to answer with in replay. So `javaType` and `sqlType`
belong to the library that wrote them: a library in another language keeps its own type names there.

Here is one entry from `sql.json`, a stored procedure with IN and OUT parameters and a result set.
The comments are added here. The real files are plain JSON.

```jsonc
{
  "seq": 2,                                   // its place in the request
  "thread": "http-nio-8081-exec-2",           // where it ran, for reading only
  "sql": "{call get_discount_rules( ?, ?)}",  // the key: this text is how calls line up
  "params": ["GOLD"],                         // IN values by position, compared by the runner
  "out": [                                    // OUT parameters, handed back in replay
    { "position": 2, "javaType": "java.lang.Integer", "value": 2 }
  ],
  "results": [                                // everything the call returned, in order
    {
      "columns": [
        { "name": "rule", "javaType": "java.lang.String", "sqlType": 12 },
        { "name": "discount", "javaType": "java.math.BigDecimal", "sqlType": 3 }
      ],
      "rows": [["LOW_CLAIMS", 0.04], ["LOYALTY", 0.06]]
    },
    { "updateCount": 1 }
  ]
}
```

Plain SQL looks the same, with the statement as `sql`. Personal data shows up as a mask such as
`masked:c3d92777e9b1`. The same input always gives the same mask, in traffic and in rows alike. So
a name read from the database and sent on to the partner still matches in replay.

## What may differ between runs

Every run generates new ids and timestamps, so a plain comparison would always fail. What counts as
noise depends on the team, so nothing here is hard-coded per case. The Python runner applies two
layers, in `tests/compare.py`:

1. **A default normaliser.** ISO timestamps and UUIDs are treated as equal wherever they appear.
   This needs no setup.
2. **One config file per service.** `regtests.json`, next to the recordings. Any team can edit it
   without touching code.

This is the file in this repo:

```json
{
  "normalize": { "timestamps": true, "uuids": true },
  "ignore": {
    "jsonPaths": ["$.quoteNumber"],
    "sqlParams": { "create_quote": [2] }
  },
  "personal": {
    "fields": ["customerName", "contactPhone"],
    "sqlParams": { "get_customer": [2, 3], "create_quote": [7] }
  }
}
```

The service makes a quote number from the clock, like `Q-1793971574424`. That is neither a UUID nor
a timestamp, so the default can't know about it. `ignore` says to skip that JSON path, and to skip
parameter 2 of `create_quote`. SQL rules are keyed by any text found in the statement, and count
parameters from 1.

`personal` is the one section the capture library reads, because masking happens at capture.
`personal.fields` adds to the names masked with no config: `fullName`, `firstName`, `lastName`,
`email`, `phone`, `address` and `dateOfBirth`. These match JSON fields and SQL column names alike,
so `full_name` matches `fullName`. Parameters have no names at the JDBC level, so a personal value
passed through a parameter must be named by position in `personal.sqlParams`. **Until it is, it is
stored as it is. Read your recordings before you commit them.**

## Code tour

Numbered comments mark each key moment. Read them in order and you follow one request through
record, replay and failure. To list them all: `grep -rn "REG-" .`

The library's Java files are all in `regtests-capture-starter/src/main/java/example/regtests/`,
shortened to `…/` below. REG-5, REG-6 and REG-25 are comments in `demo-service`. They are there for
this tour only, and a real service needs none.

| Pointer | File | What happens there |
|---|---|---|
| REG-1 | `…/RegtestsAutoConfiguration.java` | One setting decides everything. Off creates nothing |
| REG-2 | `console/stages.py` | The real database and partner start as their own processes |
| REG-3 | `…/RegtestsFilter.java` | A request arrives and gets a request id in thread context |
| REG-4 | `…/DataSourcePostProcessor.java` | In record, the real `DataSource` is wrapped |
| REG-5 | `demo-service/src/main/resources/mappers/QuoteMapper.xml` | Stored procedures through MyBatis |
| REG-6 | `demo-service/src/main/java/example/quotes/AuditWriter.java` | Plain JDBC through `JdbcTemplate` |
| REG-7 | `…/JdbcRecording.java` | One hook captures both: SQL or procedure, IN, OUT, rows |
| REG-8 | `…/OutboundHttp.java` | An outbound call is recorded under the request id |
| REG-9 | `…/WebClientCaptureFilter.java` | The same for WebClient, across reactor threads |
| REG-10 | `…/RequestIdTaskDecorator.java` | The request id is carried into `@Async` work |
| REG-11 | `…/Scrubber.java` | Personal data is masked at capture, one mask everywhere |
| REG-12 | `…/RegtestsFilter.java` | The response is captured and released to the caller |
| REG-13 | `…/CaseFiles.java` | The case is saved to disk. A replay run is written by the same code |
| REG-14 | `console/stages.py` | The database and partner are really stopped, then checked |
| REG-15 | `…/DataSourcePostProcessor.java` | In replay, the `DataSource` is swapped for the fake |
| REG-16 | `tests/test_replay.py` | The Python test sends the recorded request |
| REG-17 | `…/RegtestsFilter.java` | The header picks the case. No header is refused |
| REG-18 | `…/ReplaySession.java` | One queue per call, so threads can't upset the order |
| REG-19 | `…/JdbcReplay.java` | The fake database answers, and can't connect to anything |
| REG-20 | `…/OutboundHttp.java` | An outbound call is answered from the recording |
| REG-21 | `…/WebClientCaptureFilter.java` | The same for WebClient |
| REG-22 | `…/ReplaySession.java` | A call not in the recording is written down and fails loudly |
| REG-23 | `tests/compare.py` | The Python runner compares the run with the recording |
| REG-24 | `tests/test_replay.py` | The verdict: pass, or fail with each difference spelled out |
| REG-25 | `demo-service/src/main/java/example/quotes/QuoteService.java` | The deliberate one-line regression |

## Adapt it to a real service

1. Build the starter and publish it to your Maven repository (`mvn install` for a local try), then
   add the dependency.
2. Start your service in UAT with `REGTESTS_MODE=record`.
3. Send it requests. Add the header `X-Regtests-Case: some-name` to name a case. Without it, a case
   gets a generated name.
4. Read the recordings. Check that no personal data got through, and fill in `regtests.json`.
5. Commit the recordings.
6. In CI, CD or after rehydration, start the new build with `REGTESTS_MODE=replay` and no network, then run
   `tests/test_replay.py` with `SERVICE_URL`, `REGTESTS_RECORDINGS` and `REGTESTS_ACTUAL` set. The
   test reads what the service writes, so both need to see those two folders. Change the two ports
   in `test_database_and_partner_are_down` to match your service.

Run `record` in UAT only. It keeps bodies and rows in memory and writes them to disk.

## Other languages

There is one capture library so far, for Java. Libraries for Python and TypeScript services are
planned, so comparison rules live in one place. A library only records and answers.

**Shared by every language, written once:**

- The Python tests in `tests/`, which send each recorded request and read back the run.
- Test discovery: `discover_cases()` in `tests/test_replay.py` makes one test per case folder,
  so nobody writes a test by hand.
- All comparing, in `tests/compare.py`: the normaliser, the `regtests.json` rules, the expected and
  got report, and unexpected and never-made calls.
- The recording format described in [The format](#the-format).
- The `X-Regtests-Case` header, which names a case.

**What a new library must do:**

1. Read `REGTESTS_MODE`. With `off`, or with no setting, it must do nothing at all.
2. Hook inbound requests. Give each one an id, and keep that id with the work, including work
   handed to other threads or tasks.
3. Hook outbound calls at the lowest layer the language's clients share, as `DataSource` is for Java.
4. Mask at capture. A mask is `masked:` plus the first 12 hex digits of the SHA-256 of
   `salt:value`, and a value that already starts with `masked:` stays as it is. Mask the fields and
   parameters named in the `personal` section of `regtests.json`.
5. In `record`, write a case folder under `REGTESTS_RECORDINGS`, named by the `X-Regtests-Case`
   header, or with a generated name if there is no header.
6. In `replay`, refuse a request with no `X-Regtests-Case` header with HTTP 400, and one naming an
   unknown case with HTTP 404.
7. In `replay`, never connect to anything. Keep one queue of recorded calls per key, oldest first,
   and answer from it. The key is the `sql` text for a database call, and the method and URL path
   for an HTTP call.
8. In `replay`, a call with no answer left must be written down, then fail inside the service,
   because there is nothing to answer it with.
9. In `replay`, set the response header `X-Regtests-Run` to a new run id. Once the response is out
   and async work has finished, write the run to `REGTESTS_ACTUAL/<run id>/`.
10. Write `inbound.json` last, and atomically: write it under another name, then rename it. The
    runner takes that file as the sign that a folder is complete.

A library must not compare anything, normalise anything or decide pass or fail.

## Calls during startup: the approach

**Designed, not built yet.** Some services call out while they start: they read reference data,
fetch a token, run schema migrations, or load their config from a config server. Today those calls
can't be replayed, because there's no request to tie them to, so replay fails loudly on them.

```
 service starts --> [ startup: until Spring says the service is ready ] --> requests, one case each
                      record, in UAT: startup calls saved as one recording
                      replay: that recording answers them first
```

1. **A startup session.** Until Spring reports the service ready, a startup session stands in for
   the request, so every call made then belongs to it.
2. **Record, in UAT.** Every startup call is saved as one startup recording, next to the cases and
   masked the same way.
3. **Replay.** That recording is loaded first and answers the startup calls, so the service starts
   with no database and no network. Then each case is answered from its own recording, as today.
4. **Migrations.** Flyway and Liquibase are switched off in replay. With no database, there's no
   schema to build.
5. **Config server.** It's called before the hooks exist, so replay reads a saved copy of the
   config instead.
6. **Fails loudly.** A new or changed startup call stops replay, naming the call.

## Known limits

This is a prototype. Where something isn't handled, it aims to fail loudly.

**Startup**
- Calls during startup aren't covered yet. The approach is designed: see
  [Calls during startup](#calls-during-startup-the-approach).

**Which clients are caught**
- Anything built with `new` inside a method, and clients other than Spring's, aren't hooked, apart
  from OkHttp and Apache HttpClient 5 with their one line. See
  [What a team has to do](#what-a-team-has-to-do).
- Bodies are treated as text or JSON. Binary and streaming bodies aren't supported.
- Of the response headers, only `Content-Type` is recorded.
- A `WebClient` call must start on the request's thread, so `subscribeOn` loses the request id.

**Database**
- JPA works at this level, with these settings in replay: `spring.jpa.database-platform` set to
  your dialect, `spring.jpa.hibernate.ddl-auto=none` and
  `spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false`. It is tested with
  assigned ids only.
- Database-generated keys (`getGeneratedKeys`) and JDBC batches aren't captured. Replay fails
  loudly on them, which rules out JPA `IDENTITY` ids and batching for now.
- Ref cursors, LOBs and named `CallableStatement` parameters aren't supported.
- Transactions aren't modelled. In replay, commit and rollback do nothing and aren't compared.
- Calls to the same statement are answered in recorded order. Two threads making the same call
  with different values can swap answers, which shows up as a false difference.
- MariaDB stands in for your database. The hook only sees JDBC, but other drivers may call methods
  the proxies don't answer yet. That shows up while recording, as an error naming the method.

**Other outbound kinds aren't built.** The same pattern applies, and this is where a hook would go:

| Kind | Where a hook would go |
|---|---|
| Kafka | `ProducerInterceptor`, or a `BeanPostProcessor` around `KafkaTemplate` |
| JMS and other MQ | A `BeanPostProcessor` around `JmsTemplate` or the `ConnectionFactory` |
| Redis | A `BeanPostProcessor` around `RedisConnectionFactory` |
| MongoDB | A `BeanPostProcessor` around the `MongoClient` bean |
| Cassandra | A `BeanPostProcessor` around the `CqlSession` bean |
| SOAP | A `ClientInterceptor` on `WebServiceTemplate`, through `WebServiceTemplateCustomizer` |
| gRPC | A `ClientInterceptor` on the channel |
| Files | No single Spring hook. It needs a wrapper around the team's storage code |

**Inbound and threads**
- Only servlet HTTP requests start a case. Message listeners, scheduled jobs and WebFlux servers
  aren't covered.
- `@Async` is covered through Spring Boot's own executor. Threads and executors a team builds by
  hand lose the request id, so their calls aren't captured, and they fail loudly in replay.

**Comparison and masking**
- Every ISO timestamp counts as equal, so a wrong date in one goes unnoticed. Turn it off with
  `"timestamps": false` and list the paths instead.
- The clock isn't frozen in replay. Logic that depends on today's date can differ.
- Masking works on named fields, column names and configured positions. Personal data inside free
  text or a URL isn't found.
- The service sees masks in replay. Logic that validates a phone number's format, say, will behave
  differently.

## Debugging

**In a Codespace.** Open the repo in a GitHub Codespace. `.devcontainer/devcontainer.json` gives you
Java 17, Maven and Docker. Run the same `docker build` and `docker run` commands, and port 8080 is
forwarded for you. This config hasn't been tried in a real Codespace yet.

**With breakpoints.** Start the demo with `DEBUG=1`, which opens a debug port on `demo-service`:

```
docker run --rm -e DEBUG=1 -p 8080:8080 -p 5005:5005 regtests-prototype
```

- VS Code: use the `Attach to demo-service (port 5005)` config in `.vscode/launch.json`.
- IntelliJ: add a Remote JVM Debug configuration for `localhost:5005`.

Set a breakpoint in `QuoteService.price`, or at any REG pointer, then press Next on the page. The
service restarts between stages, so attach again after each restart.

## Troubleshooting

| What you see | What to do |
|---|---|
| `Cannot connect to the Docker daemon` | Start Docker and try again |
| `port is already allocated` | Something else uses 8080. Run with `-p 8090:8080` and open port 8090 |
| The page says it is starting | Give it about ten seconds. It starts a database and two Java processes |
| The page shows an error | It includes the end of the failing process's log. For the full logs: `docker exec <id> cat /data/logs/service.log` (`docker ps` shows the id; `database.log` and `partner.log` are there too) |
| The build fails at `mvn verify` | A unit test failed. The test name is in the output. `NoCaptureCodeTest` means code in `demo-service` mentions the library |
| `io_uring` warning in `database.log` | Harmless. MariaDB falls back on its own |
| Ctrl+C doesn't stop it | Run `docker ps`, then `docker stop <id>` |
| A case fails after you changed the service on purpose | That's the point. If the new behaviour is right, update the expected values: record the case again, or edit them in the recording by hand |
