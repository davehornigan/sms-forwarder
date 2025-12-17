# SMS Forwarder

A simple Android application that forwards all incoming SMS messages to a specified webhook URL.

## Features

- Forwards all incoming SMS messages to a webhook.
- Customizable webhook URL.
- Customizable `User-Agent` header for requests.
- Support for multiple SIM cards with customizable slot names.
- Statistics screen to monitor forwarded messages (total, successful, failed).
- Error log for easy debugging.

## How to Use

1.  Install the `.apk` on your Android device.
2.  Open the app. You will be prompted to grant the necessary permissions (Receive SMS, Read Phone State/Numbers).
3.  Navigate to the **Settings** screen by tapping the gear icon.
4.  Enter your desired **Webhook URL**.
5.  (Optional) Set a **Custom User-Agent** if your server requires it.
6.  (Optional) If you have multiple SIM cards, you can assign a custom name to each slot.
7.  Tap **Save**.

The application is now configured. It will run in the background and forward every SMS it receives.

## Webhook Payload

The application will send a `POST` request to your webhook URL with a JSON payload in the following format:

```json
{
  "sender": "+1234567890",
  "body": "This is the SMS message content.",
  "recipient": "+0987654321",
  "simSlotName": "Personal SIM"
}
```

### Field Descriptions

- `sender`: The phone number of the message sender.
- `body`: The full text content of the SMS. Long messages are automatically concatenated.
- `recipient`: The phone number of the recipient (your device's number). *Note: This field may be an empty string (`""`) due to Android limitations, as the phone number is not always accessible programmatically.*
- `simSlotName`: The custom name you assigned to the SIM slot (e.g., "Work SIM") or the default name (e.g., "SLOT #1") if not configured.
