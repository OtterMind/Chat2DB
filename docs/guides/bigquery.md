# Connect Chat2DB Community to Google BigQuery

This guide shows how to add a BigQuery connection in Chat2DB Community and run your first query.

BigQuery is a fully managed cloud data warehouse from Google. Chat2DB connects to it through the Simba JDBC driver and authenticates using a Google Cloud service account.

## Prerequisites

Before you start, you need:

- A Google Cloud project. A billing account is recommended for full functionality; BigQuery's [sandbox](https://cloud.google.com/bigquery/docs/sandbox) lets you try the service without a billing account, with stricter limits. The BigQuery free tier covers the first 1 TiB of query data processed and 10 GiB of storage per month, so the steps in this guide stay inside the free tier.
- A service account with the `roles/bigquery.user` role (BigQuery User), or an equivalent role that lets the account run jobs and read data.
- A service account key in JSON form. The guide explains how to create one below.
- Chat2DB Community running locally, through Docker, or as the desktop app. See the main [README](../../README.md) for setup.

You do not need a Java or Maven setup to follow this guide. Chat2DB downloads the JDBC driver on first connection.

## Create a service account key

The service account is what Chat2DB uses to authenticate to BigQuery. Treat its JSON key like a password.

### Prerequisites

Before you start, your user account needs the Service Account Key Admin role (`roles/iam.serviceAccountKeyAdmin`) on the project, per the [Google Cloud IAM documentation](https://cloud.google.com/iam/docs/keys-create-delete). Your organization may also enforce a policy that disables key creation; if so, follow the policy override steps in the same page.

### Steps in the Google Cloud console

The official Google Cloud documentation sends you through a guided console walkthrough rather than enumerating each step inline, because the console UI changes over time. The current path is roughly:

1. In the Google Cloud console, open **IAM and Admin → Service Accounts** for the project you want to connect to.
2. Click **Create service account**, give it a name such as `chat2db-reader`, and click **Create and continue**.
3. Grant the **BigQuery User** role (`roles/bigquery.user`), then click **Continue** and **Done**.
4. Open the new service account, switch to the **Keys** tab, click **Add key → Create new key**, and choose **JSON**. The browser downloads a file that ends in `.json`.
5. Store the file somewhere only your user account can read, for example `~/.config/chat2db-community/keys/`.

If your console layout looks different, follow the [official walkthrough](https://cloud.google.com/iam/docs/keys-create-delete) instead — it always reflects the current UI.

Do not commit this file to any repository, and do not paste its contents into chat, screenshots, or issue trackers. If the file leaks, delete the key in Google Cloud and create a new one.

## Connection configuration

In Chat2DB, open the connection dialog and choose **BigQuery** as the database type. The form shows the fields below. Every field is taken from the current Community plugin defaults at `chat2db-community-server/chat2db-community-plugins/chat2db-community-bigquery/src/main/resources/ai/chat2db/plugin/bigquery/bigquery.json` and the field wiring in `BigQueryDBManager.java`.

| Field | What to enter | Notes |
| --- | --- | --- |
| URL | `jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443` | Pre-filled by the plugin. Leave as the default unless your network requires a proxy. |
| Project | Your GCP project ID | The project that owns the data you want to query, or a project the service account can read from. |
| Email | The service account's `client_email` | You can find it inside the JSON key file under the `client_email` field. |
| Keyfile | Absolute path to the JSON key file on disk | Chat2DB passes this value to the JDBC driver as the `OAuthPvtKeyPath` property. The driver reads the file from disk, so a path is required. |
| Database | Optional | Passed through to the JDBC connection as the database property. BigQuery treats datasets as the closest equivalent to a database in the SQL sense, but the plugin does not translate this value explicitly. Leave blank to query across datasets. |
| Schema | Optional | Same status as Database. BigQuery does not have a separate schema namespace inside a dataset. |

The plugin translates your input to the following JDBC properties before opening the connection:

- `ProjectId` from the **Project** field.
- `OAuthServiceAcctEmail` from the **Email** field.
- `OAuthType=0` and `OAuthPvtKeyPath` from the **Keyfile** field. The `OAuthType=0` value selects service-account authentication in the Simba driver.

Chat2DB Community ships support for the service account flow only. OAuth user flows (browser sign-in, OAuth refresh tokens) are not currently wired up in the Community plugin, so this guide does not document them.

## Test the connection

Click **Test connection** in the dialog. A success message confirms that the driver downloaded, the service account authenticated, and the project is reachable.

To run the cheapest safe query once the connection is saved, open a new SQL tab and run:

```sql
SELECT 1 AS ok;
```

This query does not read any tables, does not scan data, and does not require any dataset to exist. It processes essentially zero bytes, which puts it inside BigQuery's free tier, so it does not bill the project.

## Query example

BigQuery uses backtick-quoted fully qualified table names of the form `` `project.dataset.table` ``. A useful first query is to list tables in a dataset:

```sql
SELECT table_name
FROM `your-project.your_dataset.INFORMATION_SCHEMA.TABLES`
ORDER BY table_name
LIMIT 50;
```

Replace `your-project` with your GCP project ID and `your_dataset` with a dataset the service account can read. To run the query, press **Run** or use the keyboard shortcut configured in Chat2DB.

## Troubleshooting

These are the failures users hit most often when connecting Chat2DB Community to BigQuery. Each fix is based on either the plugin's own configuration (`bigquery.json`) or the way the plugin maps form fields to JDBC properties (`BigQueryDBManager.java`). The exact error text returned by the Simba driver and Google APIs can vary between versions, so match by category rather than verbatim wording.

### Driver class not found on first connect
The Simba JDBC driver archive is downloaded the first time Chat2DB talks to a BigQuery connection. If the runtime cannot reach `cdn.chat2db-ai.com`, the download fails and the connection cannot open. Allow that host, then click **Test connection** again. The driver archive filename is `SimbaJDBCDriverforGoogleBigQuery42_1.6.1.1002.zip` and the driver class is `com.simba.googlebigquery.jdbc42.Driver`, both defined in `bigquery.json`.

### Access denied or permission errors
The service account is missing the `roles/bigquery.user` role, or the project ID in the form does not match a project the service account can see. Re-check the role grant in IAM and Admin and the value in the **Project** field.

### Invalid JWT, invalid grant, or private key parsing errors
The Keyfile value is wrong. The plugin passes it straight to the JDBC property `OAuthPvtKeyPath`, which the Simba driver treats as a path to a JSON file on disk. Confirm the path is absolute, the file still exists, and you have not edited the JSON by hand.

### Project not found or BigQuery not enabled
The **Project** field does not match a real GCP project, or BigQuery is not available for that project. Project IDs are case-sensitive and never start with `google.com`. If the project is brand new, confirm whether billing is linked or whether you want to use the [BigQuery sandbox](https://cloud.google.com/bigquery/docs/sandbox) without one.

### Billing or sandbox errors
BigQuery normally requires a billing account to be linked to the project. If you prefer to skip billing, the [BigQuery sandbox](https://cloud.google.com/bigquery/docs/sandbox) lets you run queries against up to 10 GiB of free storage and 1 TiB of free query processing per month, without attaching a billing account. Either way, queries that scan data still cost the project once the free tier is exhausted. Open **Billing** for the project in Google Cloud and link an active billing account if you need to go beyond the sandbox limits.

### Cannot reach `googleapis.com`
The runtime cannot reach Google APIs. Chat2DB Community's desktop runtime is offline-first by default and will not connect out unless you switch the network profile. If you are running the desktop app, choose a network where `googleapis.com` is reachable before testing the connection.

## Security notes

- The service account JSON key is a credential. Store it where only the Chat2DB process owner can read it, and never commit it to a Git repository. Add paths such as `*.json` under `~/.config/chat2db-community/keys/` to your personal shell history exclusion if you ever pass them on the command line.
- Chat2DB encrypts stored datasource fields at rest with AES-256-GCM using the key in `~/.config/chat2db-community/encryption.key`. That encryption protects saved connections, not the keyfile you point to on disk.
- Grant the service account the smallest role that works. `roles/bigquery.user` is enough to run jobs and read query results. Grant `roles/bigquery.dataViewer` or `roles/bigquery.dataEditor` only on datasets that need them.
- If you ever paste the JSON contents in a chat, screenshot, or issue tracker, treat the key as compromised. Open Google Cloud, delete the key under the service account's **Keys** tab, and create a new one.
