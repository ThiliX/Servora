# User guide

For the people who run customer support on Servora: business owners, admins, supervisors and agents. To install and operate Servora itself, see the [README](../README.md) and [docs/operations](operations/production.md).

## Roles

Each workspace is one business. People join it by invitation, with one role:

| Can… | Owner | Admin | Supervisor | Agent |
|---|---|---|---|---|
| Use the inbox and reply to customers | ✓ | ✓ | ✓ | ✓ (tickets assigned to them, or unassigned) |
| See all conversations, assign tickets, see the Overview | ✓ | ✓ | ✓ | |
| Manage documents, the assistant, the widget, team and settings; see Usage and the Audit log | ✓ | ✓ | | |
| Set the retention period, delete conversations, export data | ✓ | | | |

The dashboard has light and dark themes: choose Light, Dark or System at the bottom of the sidebar.

## 1. Set up a workspace

1. Register, open the link in the verification email, and sign in.
2. Create a workspace: the business name and a short web address for it.
3. **Settings**: time zone, language, industry and logo.
4. **Team**: invite colleagues by email with a role. They join through the link in the invitation.

## 2. Add your knowledge

**Knowledge** → upload PDF, TXT or Markdown files (up to 10 MB each; scanned PDFs without a text layer cannot be read). Each file goes from Uploaded through Processing to **Ready**. Only Ready documents are used. Replacing a document keeps answering from the old version until the new one is ready. Archive or delete documents that are out of date: the assistant answers only from what is here.

Write documents the way customers ask: short sections with clear headings ("Late check-out", "Refunds for faulty items") work best. The [evaluation report](ai/evaluation-report.md) shows that questions using very different words from the documents are the most common reason the assistant hands over to a person.

## 3. Configure the assistant

**Assistant**: its name, welcome message, the fallback message it gives when it cannot help, language, tone, and the confidence policy:

- **Balanced** answers when it finds reasonably good evidence.
- **Strict** needs stronger evidence and hands more questions to a person.

Every change is saved as a new version. **Playground** lets you ask questions and see the answer, its sources and why it decided to answer or hand over, before customers do.

## 4. Put the chat on your website

**Widget**: title, colour and position. Add the website addresses allowed to show it, for example `https://www.example.com`, then copy the embed snippet into your website's pages. The widget refuses to load on any other website. **Replace key** stops the old snippet working.

If you choose a light theme colour, check that white text on it is still easy to read.

## 5. Handle conversations

The assistant answers when your documents support an answer, and cites them. Otherwise, or when the customer asks for a person, is upset, or needs something done (a refund, a booking change), it says a colleague will help and opens a **ticket**.

- **Inbox**: tickets, with priority and status. Supervisors and above assign them.
- Open a ticket and **Take over** the conversation: from then on the assistant stops replying and only people answer.
- **Internal notes** (the Internal note tab) are visible to your team only, never to the customer.
- **Draft with AI** asks the assistant for a draft that you check and edit before sending.
- **Resolve** the ticket with an outcome. The customer can then rate the conversation.

**Conversations** shows every conversation, including those the assistant handled alone.

## 6. Follow how it goes

- **Overview**: conversations, how many the assistant resolved, escalations, response times and ratings, for 7, 30 or 90 days.
- **Usage**: AI requests, tokens, conversations and documents against your plan's monthly limits, with an estimated cost.
- **Audit log**: who changed what, and when.

When a plan limit is reached, the assistant hands new questions to your team instead of answering until the next month.

## 7. Data and privacy

Owners, under **Settings**:

- **Retention**: conversations older than the period you choose (30 days to 10 years) are deleted automatically.
- **Delete** a single conversation, with its messages and AI records.
- **Export workspace data** as a file.

With a real AI model, customers' questions and the matching passages from your documents are sent to the model provider (Google or Anthropic) to write the answer. Do not upload documents containing information that must not leave your organisation.

## Known limits

- The assistant answers each message on its own; it does not use earlier messages in the conversation.
- Email notifications are not built: verification and invitation emails need an administrator's help, and alerts appear in the dashboard only.
- Escalation rules are built in, and the list of risky words is English only.

The full list is in the [README](../README.md#limitations).
