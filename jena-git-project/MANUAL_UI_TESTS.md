# Manual UI Test Cases

Run before showing the demo. Start the app with `mvn spring-boot:run` in
`jena-git-project` and open <http://localhost:8080>.

## 1. Health

Open `/api/health`. Expected: `"status": "UP"`, `triples` above 190 000,
`missingFiles` empty, `dailyUpdate.enabled` false (unless switched on).

## 2. Legal acts

1. Open `/`. Expected: about 1 116 acts, 4 212 versions, 7 854 relations; newest acts first.
2. Type `cura` or `20G00034` in the search box and press Filter. Expected: D.L. 17 marzo 2020, n. 18.
3. Choose Type = Decreto-legge and Conversion status = PENDING. Expected: only recent decree-laws, all PENDING.
4. Press Clear. Expected: the full list again; Next/Previous move between pages.

## 3. Act details: a decree-law

Open D.L. 17 marzo 2020, n. 18 (`/eli/id/2020/03/17/20G00034/sg`). Expected:

- Check against Gazzetta Ufficiale: MATCH.
- Conversion into law: CONVERTED, trust A, converted by L. 24 aprile 2020, n. 27 in 43 of 60 days, checks listed.
- Versions: 88, the original text first; "Show the other … versions" opens the rest.
- What other acts did to this act: amendments with trust level, textual modifications and the editor's note.
- Every link to another act opens its page on the same host.

## 4. Act details: the conversion law

Open L. 24 aprile 2020, n. 27 (`/eli/id/2020/04/29/20G00045/sg`). Expected: the
section "Decree-laws this law converts" lists D.L. 18/2020 as CONVERTED; the
relations show the decree-laws it repeals (D.L. 9, 11, 14/2020).

## 5. Linked data

1. On an act page click "View as RDF (Turtle)". Expected: Turtle with `eli:` properties.
2. `curl -H "Accept: text/turtle" http://localhost:8080/eli/id/2020/03/17/20G00034/sg` returns the same Turtle.
3. Open a version link (e.g. "Original text"). Expected: the generic resource page with "The act" link back.
4. Open `/eli/id/1999/01/01/XXXX/sg`. Expected: "Nothing at this address" with a link to the list.

## 6. SPARQL

1. Open `/query` (or `/sparql` in a browser, which redirects there).
2. Choose example `08-conversions` and press Run example. Expected: 249 rows; act links open act pages.
3. Click CSV. Expected: a CSV download from `/sparql`.
4. Run `SELEC x`. Expected: a red "not valid SPARQL" message, no error page.
