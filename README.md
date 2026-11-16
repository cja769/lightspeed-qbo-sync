# Lightspeed to QuickBooks Sync

Dockerized Spring Boot connector that maps Lightspeed daily sales into the normalized model from `pos-qbo-journal-core`. The default mode uses fixture input and an in-memory QuickBooks client.

## Run the mock

Clone this repository and `pos-qbo-journal-core` as sibling directories, then run:

```bash
docker compose up --build
```

## Persistent state

The container stores atomic sync receipts under `SYNC_DATA_DIRECTORY` (`/data` in Compose). Keep the service single-instance and retain the named Docker volume. The shared reconciliation planner supports daily checks plus Sunday, monthly, quarterly, and annual verification windows. Default business time zone is `America/Chicago` and is configurable with `SYNC_TIMEZONE`.

## First live dry run

1. Build the app with the shared core checked out as a sibling, the same layout
   used by Compose. From the core run `mvn install`, then run `mvn package` here.
2. Set `SYNC_MOCK_ENABLED=false`, `SYNC_ACCOUNT_MAPPING_FILE` and the source
   settings below. Keep secrets in the runtime environment or a secrets manager.
3. Start with `SYNC_WRITE_MODE=dry-run` and `SYNC_CRON=-`. Run
   `java -jar target/lightspeed-qbo-sync-0.1.0-SNAPSHOT.jar --sync-date=YYYY-MM-DD`
   for a completed business day. Verify the proposed journal against the source
   reports, including every tender, tax and refunds.
4. To include QuickBooks lookups, set `QBO_REALM_ID`, `QBO_CLIENT_ID`,
   `QBO_CLIENT_SECRET` and `QBO_ENVIRONMENT` (`sandbox` by default).
   Authorize the realm for the Intuit accounting scope and seed its refresh token
   in `SYNC_DATA_DIRECTORY/qbo-<realm-id>.token` with owner-only permissions.
   Set `QBO_EXPECTED_COMPANY` to the exact company name returned by QuickBooks.
   The app verifies that name before accessing journal entries.

The mapping file has two objects. `accounts` maps
`SALES_REVENUE`, `DISCOUNTS`, `RETURNS`, `SALES_TAX_PAYABLE`, `TIPS_PAYABLE`,
`GIFT_CARD_LIABILITY`, `PROCESSING_FEES`, and `TENDER_CLEARING` to QuickBooks
account IDs. `tenders` maps each exact source tender key to an account ID. See
the source notes below for the keys. Use each company's own account IDs.

After a verified sandbox post and a live dry-run tie-out, set
`SYNC_FIRST_POST_DATE=YYYY-MM-DD` past all existing sales postings, and
`SYNC_WRITE_MODE=post`. Use a first-of-month cutover if the existing sales journal
is monthly. A rerun updates managed entries only. Review older entries manually
before choosing a backfill date. Set `SYNC_CRON='0 0 3 * * *'` to keep the app
running on a daily schedule in `SYNC_TIMEZONE` (default America/Chicago). Without
a cron it runs once. Use separate persistent data volumes for the two apps. A
Compose deployment can supply secrets through an ignored `.env` file, mount
configuration read-only, and set `SYNC_RESTART_POLICY=unless-stopped` when using cron.

The jobs post sales journals to QuickBooks. Bank-feed matching and QuickBooks
statement reconciliation are handled separately.

## Lightspeed source settings

- `LIGHTSPEED_SHOP` is the Retail X shop domain prefix.
- `LIGHTSPEED_ACCESS_TOKEN` needs the `sales:read` scope.
- `LIGHTSPEED_OUTLET_ID` is the outlet for this job. Tender keys are the
  `payments[].type.config_id` values from the Sales API.

The job searches closed receipts using the outlet and the local business day,
checks receipt and payment totals, and posts signed refunds and tax. Unrecognized
fulfillment, surcharges, e-commerce custom charges, gift card sales and adjustments
cause the day to fail for review. Match processor fees and payouts using the
merchant settlement reports, which this connector does not retrieve.
