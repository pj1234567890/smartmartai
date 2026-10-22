# Goods Receiving (manager only)

SmartMart's **Goods Receiving** module lets a store manager take in a supplier delivery:
upload the supplier invoice, extract its contents, scan the physical cartons, reconcile the
two, and commit the accepted stock **exactly once** with a full audit trail.

Stock never moves because a document was uploaded, an extraction ran, or a carton was
scanned. **Only a manager confirming an eligible receipt changes inventory.**

---

## 1. Workflow and receipt states

```
DRAFT ──upload/extract──▶ PROCESSING ──▶ REVIEW_REQUIRED ──▶ READY_TO_CONFIRM ──▶ CONFIRMED
   │                                                │                              │
   └── manual lines, carton scans, corrections ─────┘                     cancel ──▶ CANCELLED
```

| State | Meaning |
|---|---|
| `DRAFT` | Receipt created; supplier/invoice data can still be typed in. |
| `PROCESSING` | An invoice upload is being extracted. |
| `REVIEW_REQUIRED` | Extraction finished, or the manager changed something; discrepancies may still be open. |
| `READY_TO_CONFIRM` | All mandatory checks pass: every line is matched to a product, quantities are converted, every line has an explicit accepted quantity (with a reason when it differs from the invoice), and accepted lines carry a batch code. |
| `CONFIRMED` | Stock was committed. The receipt can no longer be edited or cancelled. |
| `CANCELLED` | The delivery was abandoned before confirmation. |

Invalid transitions are rejected with a clear error (HTTP 409) — e.g. confirming a
`REVIEW_REQUIRED` receipt, or editing a `CONFIRMED` one.

## 2. Architecture and data flow

```
frontend/src/components/receiving/*   (React, hash routing, manager dashboard tab)
        │  frontend/src/services/api.js  (shared API client, JWT bearer token)
        ▼
ReceivingController  (@PreAuthorize("hasRole('MANAGER')") on every endpoint)
        ▼
ReceiptService ── CartonService ── InvoiceExtractionService ── ReconciliationService
        │                │                    │                        │
        │                │                    └─ InvoiceTextParser ── ProductMatcher
        │                └─ QrLabelService (ZXing)
        ▼
StockConfirmationService  ──►  ProductBatchService + InventoryService (existing stock tables)
        │                        + stock_movements (audit)
        ▼
PostgreSQL
```

* **Backend package** `com.smartmart.receiving` → `controller`, `service`, `repository`,
  `entity`, `dto`, `config`.
* **Quantity math is backend-owned.** The frontend sends decisions (accepted quantity,
  reason, product choice); every expected/scanned/accepted total is recomputed on the server
  from persisted carton rows.
* **QR data is never trusted.** A scanned payload is only accepted when the carton id
  exists, belongs to this receipt, and has not been scanned before.

## 3. Database tables

> **Schema strategy:** this project does **not** use Flyway. Schema is managed by
> Hibernate (`spring.jpa.hibernate.ddl-auto: update` in `application.yml`), the same
> strategy the rest of SmartMart already uses. There are no migration scripts to run, and
> no migration files should be claimed. When the schema changes, restart the backend against
> the target database and let Hibernate add the new tables/columns.

| Table | Purpose |
|---|---|
| `goods_receipts` | One supplier delivery: supplier, invoice no/date, PO ref, status, created/confirmed by, timestamps, notes. |
| `receipt_documents` | Uploaded invoice metadata: sanitised filename, mime type, size, storage reference, processing status, extracted text, warnings/errors, upload timestamp. |
| `receipt_lines` | Invoice line + reconciliation: product, description, sku, invoice quantity, unit of measure, units per carton, expected units, scanned quantity, accepted/rejected quantity, cost, total, batch code, expiry, match status, discrepancy status, plus `original_*` columns that keep the extracted values for audit. |
| `receipt_cartons` | One physical carton: unique `carton_id`, receipt, line, batch, declared units, accepted units, scan timestamp, status (`ISSUED`, `SCANNED`, `VERIFIED_SHORT`, `REJECTED`), discrepancy reason. |
| `product_batches` | Batch/lot per product: batch code, expiry date, unit cost, quantity on hand, unique on `(product_id, batch_code)` so the same batch arriving twice reuses one row. |
| `stock_movements` | Audit trail: product, batch, movement type, quantity delta, reference type/id, acting manager, timestamp, reason. |

Existing `products` and `inventory` tables are reused — no duplicate product or stock tables
were introduced. `inventory.quantity` remains the aggregate product-level quantity that the
existing low-stock, stock-out, discount and availability features already read; a confirmed
receipt updates it together with the batch row.

## 4. Backend endpoints

All endpoints live under `/api/receiving` and are **manager-only**.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/receiving` | Create a draft receipt. |
| `GET` | `/api/receiving?page=&size=&status=` | List receipts (paginated, optional status filter). |
| `GET` | `/api/receiving/{id}` | Receipt detail: lines, cartons, batches, extraction status, discrepancies, blockers. |
| `PATCH` | `/api/receiving/{id}` | Update permitted draft-level metadata. |
| `POST` | `/api/receiving/{id}/invoice` | Upload an invoice image/PDF (`multipart/form-data`, field `file`). |
| `POST` | `/api/receiving/{id}/extract` | Run or retry extraction. |
| `POST` | `/api/receiving/{id}/lines` | Add a manually entered line. |
| `PATCH` | `/api/receiving/{id}/lines/{lineId}` | Correct a line (product, quantity, batch, expiry, accepted/rejected quantity, reason). |
| `POST` | `/api/receiving/{id}/cartons/labels` | Generate carton QR labels (PNG, base64) for a line. |
| `POST` | `/api/receiving/{id}/cartons/scan` | Register a scanned carton (QR payload or manual carton code). |
| `POST` | `/api/receiving/{id}/cartons/{cartonId}/verify` | Record a physically verified carton quantity (shortage/rejection). |
| `GET` | `/api/receiving/{id}/cartons` | List scanned cartons. |
| `DELETE` | `/api/receiving/{id}/cartons/{cartonId}` | Remove an erroneous scan from an unconfirmed receipt. |
| `GET` | `/api/receiving/{id}/discrepancies` | Actionable discrepancy list. |
| `POST` | `/api/receiving/{id}/reconcile` | Recompute reconciliation from persisted data. |
| `POST` | `/api/receiving/{id}/confirm` | Confirm and commit stock (idempotent). |
| `POST` | `/api/receiving/{id}/cancel` | Cancel an unconfirmed receipt. |
| `GET` | `/api/receiving/{id}/movements` | Stock movements committed by this receipt. |
| `POST` | `/api/receiving/{id}/batches` | Create a batch record for a product/batch code. |

## 5. Frontend

* Navigation: **Manager dashboard → Goods Receiving** (`frontend/src/components/layout/Layout.jsx`).
* Screens: `frontend/src/components/receiving/`
  * `ReceivingPage.jsx` – overview list, status filter, new-receipt setup, invoice upload.
  * `InvoiceReview.jsx` – extracted values (editable), per-line product matching, warnings.
  * `CartonScanner.jsx` – camera scanning via `@zxing/browser`, manual carton-code entry,
    duplicate/invalid feedback, scanned list with declared-unit totals.
  * `ConfirmPanel.jsx` – reconciliation table, discrepancy resolution, confirmation summary.
* All calls go through the existing shared client `frontend/src/services/api.js`
  (`receiving`, `createReceipt`, `receiptDetail`, `uploadInvoice`, `extractInvoice`,
  `addReceiptLine`, `updateReceiptLine`, `generateLabels`, `scanCarton`, `verifyCarton`,
  `removeCarton`, `reconcile`, `confirmReceipt`, `movements`, …).

### Camera access

Camera scanning uses `getUserMedia`, which browsers only allow on **HTTPS or
`localhost`**. On plain HTTP over a LAN address the camera cannot start; the manual
carton-code entry (and every other part of the module) keeps working. The camera is only
requested when the manager presses *Start scanning* and the stream is stopped when the
manager stops scanning or leaves the screen.

## 6. QR labels

* Payload (version 1):
  `{"version":1,"cartonId":"CTN-0001042","productId":7,"batchCode":"MILK-2026-10-B02","unitsPerCarton":12}`
* Generated server-side with **ZXing** (`QrLabelService`), returned as a base64 PNG per
  carton; the manager dashboard can print them.
* Only products/batches that exist in SmartMart can be labelled, and the declared unit
  count must be positive.
* **A QR scan proves the label, not the contents.** The scanned quantity is the *declared*
  quantity until a manager physically verifies a carton (`verifyCarton`) and records a
  different accepted quantity with a reason.

## 7. Invoice extraction and OCR

1. **Searchable PDF** → native text extraction with **Apache PDFBox** (no OCR, no cost).
2. **Image-only PDF or photo (JPG/JPEG/PNG)** → pages rendered at 300 DPI and read by
   **Tesseract** through **Tess4J**.
3. The text is parsed by a modular parser (`InvoiceTextParser`) into a draft invoice.
4. Lines are matched to products (`ProductMatcher`): exact product id → exact SKU/name →
   normalized description → manual selection. Similar names are **never** merged
   automatically; ambiguous lines are flagged for the manager.
5. Fields that cannot be read stay empty and are reported as warnings. Quantities, prices,
   batch codes and expiry dates are never guessed.

**OCR is optional at runtime.** If Tesseract is missing, the upload still succeeds and
extraction fails with a clear message ("enter the invoice details manually"); the draft,
its manual lines and the whole rest of the workflow stay usable. Extraction failures are
recorded on the document as `FAILED` with the reason, so the receipt is never left in an
ambiguous state, and extraction can simply be retried.

### Installing Tesseract

* Windows: install **UB Mannheim Tesseract OCR** and note the install folder (e.g.
  `C:\Program Files\Tesseract-OCR`).
* macOS: `brew install tesseract`.
* Debian/Ubuntu: `sudo apt install tesseract-ocr tesseract-ocr-eng`.

Then point SmartMart at the **tessdata** folder (the one containing `eng.traineddata`):

```bash
# .env
SMARTMART_TESSDATA=/usr/share/tesseract-ocr/5/tessdata   # Linux
SMARTMART_TESSDATA=C:/Program Files/Tesseract-OCR/tessdata   # Windows
```

The backend validates that `eng.traineddata` exists in that folder and otherwise reports
"Tessdata folder not found at the configured path" instead of failing obscurely.

## 8. Configuration

All settings are optional and can be supplied as environment variables (or in `.env`,
which `application.yml` imports):

| Variable | Default | Meaning |
|---|---|---|
| `SMARTMART_UPLOAD_DIR` | `${user.home}/.smartmart/invoices` | Where uploaded invoices are stored (never in the database, never web-accessible). |
| `SMARTMART_MAX_UPLOAD_MB` | `10` | Maximum invoice upload size (the servlet layer uses the same variable, default 12 MB). |
| `SMARTMART_OCR_ENABLED` | `true` | Set to `false` to disable OCR entirely (manual entry only). |
| `SMARTMART_TESSDATA` | *(empty)* | Tessdata folder for Tesseract. |
| `SMARTMART_TESS_LANG` | `eng` | Tesseract language. |

Uploaded files are stored under a random UUID name; the original filename is sanitised and
kept only as metadata. Internal storage paths are never returned by the API, and invoice
contents are never logged.

## 9. Running it

```bash
# backend (PostgreSQL must be running; see .env.example)
cd smartmart
./mvnw spring-boot:run          # Windows: ./mvnw.cmd spring-boot:run

# frontend
cd smartmart/frontend
npm install
npm run dev                     # http://localhost:5174
```

Open the manager dashboard → **Goods Receiving**.

## 10. Demo walkthrough

1. Sign in as a manager (see the root `README.md` for how to promote a user to MANAGER).
2. **New receipt** → enter supplier and invoice number → create the draft.
3. **Upload invoice** → attach a printed invoice photo or a PDF. Press **Extract**.
   Correct anything the parser mis-read in the review table, and pick the SmartMart product
   for each line (unmatched lines are highlighted).
4. **Print carton labels** for a line (choose units per carton, e.g. 12) and scan them with
   the camera — or type the carton code manually.
5. Scan a carton twice to see the duplicate rejection; scan a foreign/unknown code to see the
   invalid-code error.
6. With 120 units invoiced and nine cartons of 12 scanned, the reconciliation shows a
   **shortage of 12 units** (108 scanned vs 120 invoiced). Nothing was added to stock.
7. Set the accepted quantity to 108 (or another verified number) and record a reason.
8. **Confirm** → exactly 108 units are added to the batch and to the product's aggregate
   inventory, with one `RECEIPT` stock movement per line.
9. Press **Confirm** again → the receipt is already confirmed; stock does **not** increase
   again.
10. Open the existing inventory screen → the new quantity is visible, and the stock movement
    history explains why it changed.

## 11. Tests

```bash
# everything (backend) - runs against in-memory H2, no PostgreSQL needed
cd smartmart
./mvnw test

# only the receiving module
./mvnw test "-Dtest=InvoiceTextParserTest,QrLabelServiceTest,ProductMatcherTest,UnitConversionTest,ReconciliationServiceTest,StockConfirmationIntegrationTest,InvoiceExtractionIntegrationTest,ReceivingSecurityTest"

# frontend production build
cd frontend && npm run build
```

`src/test/resources/application.yml` points the test context at in-memory H2 (PostgreSQL
compatibility mode) so the transactional integration tests run anywhere. The tests cover:

* QR payload parsing/validation, invoice parsing, product matching, unit conversion,
  quantity reconciliation, duplicate-carton detection, receipt state transitions.
* Service behaviour: creating receipts, adding lines, registering unique cartons, removing
  erroneous scans, resolving discrepancies, cancelling, rejecting edits to a confirmed
  receipt, rejecting confirmation while blockers are open.
* Critical inventory behaviour: stock increases exactly once, a repeated confirmation adds
  nothing, a mid-transaction failure rolls back batch quantities, aggregate inventory and
  stock movements (and the receipt can be retried afterwards), duplicate scans do not
  inflate totals, two concurrent confirmations cannot double-commit, and a shopper token
  cannot reach the manager endpoints.
* Upload/extraction: searchable PDF parsed via native text, unsupported/empty/corrupt/oversized
  files rejected, and a missing OCR engine degrading gracefully with the draft still editable.

## 12. Known limitations

* **No Flyway migrations exist.** The project relies on Hibernate `ddl-auto: update`
  (see §3). Back up the database before the first start against a database that already
  holds data.
* **OCR needs a local Tesseract install** and is not exercised by the automated tests
  (the test profile disables OCR and asserts the graceful-degradation path). Searchable
  PDFs are covered end to end.
* **OCR accuracy is not guaranteed**; every extracted value is editable and unmatched or
  uncertain fields are flagged rather than guessed.
* **Invoice layouts vary** between suppliers. The parser uses independent, modular field
  patterns, but unusual layouts need the manager to correct values manually.
* **A scanned QR never proves the physical contents** of a carton — the quantity is
  *declared* until a manager verifies it.
* **Unit conversion only happens when it is explicit** (e.g. `CARTON` with a stated units
  per carton). Weight/volume units are never converted; the line is blocked for review.
* Camera scanning requires HTTPS or `localhost`.
* Discounts and purchase prices are not changed automatically by a receipt; the module only
  moves stock and records batches and movements.
