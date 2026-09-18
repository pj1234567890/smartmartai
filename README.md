# SmartMart AI

Run the backend with `./mvnw.cmd spring-boot:run` from this folder and the frontend with `npm run dev` from `frontend`. Configure PostgreSQL with `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; configure a stable, 32-byte-or-longer `JWT_SECRET`. The frontend runs on port 5174 and the API on port 8080.

## Development demo data

Set `SMARTMART_DEMO_SEED=true` before the first backend start to load an empty development database with 24 products, live inventory states, two shoppers, wishlists, and completed orders. The seeder is opt-in, does not delete anything, and exits if the catalogue already has data. Demo shoppers use `DemoPass123`.

All registrations remain shoppers. To make a development manager, register normally, then run:

```sql
UPDATE users SET role = 'MANAGER' WHERE email = 'manager@smartmart.com';
```

Log out and back in to receive a MANAGER JWT. There is no public manager registration path.

## Intelligence features

- Recommendations are transparent rules, not claimed machine learning: they rank in-stock products using the shopper's category affinity, wishlist, order co-occurrence (frequently bought together), and active discounts.
- Stock-out risk uses the last 30 days of order-item sales velocity. Where no sales exist it falls back to stock, reorder level, and wishlist demand. Reorder quantities target roughly 21 days of observed demand or three reorder-levels, whichever is higher.
- Expiry intelligence classifies inventory from its real expiry date and recommends monitor, warn, prioritize sale, or discount based on days remaining, quantity, demand, and reorder level. It never automatically changes inventory or prices.
- `GET /api/intelligence/stock-out-risks`, `/reorder-recommendations`, and `/expiry` are manager-only. `GET /api/recommendations` is shopper-only.
- The assistants are grounded: backend services assemble only relevant, authenticated shopper data or aggregated manager data before responding. `AiProvider` is a provider boundary for a configured LLM; the default provider is disabled so no data leaves the application and a factual local fallback answers from current SmartMart records. API keys are never hardcoded.

The current implementation uses REST refresh after checkout; WebSocket push is intentionally not enabled yet.
