# MT App

MT App is a lightweight mobile application that helps students quickly check their attendance and receive updates from the college portal. The app automates the same actions a student normally performs in a browser and presents the information in a simpler interface.

## Features

### 1. Attendance Extraction

MT App retrieves attendance data from the college website by replicating the same requests that a browser makes when a student logs into the portal.

* The app **automates the normal student workflow** used on the website.
* It does **not bypass authentication** or access any hidden systems.
* The app simply sends the same API requests that are triggered when a student views attendance in the browser.

### 2. Privacy First

* The application **does not operate any private backend server**.
* **No login credentials or personal data are stored by the developer**.
* All requests are made **directly from the user’s device to the college website**, similar to accessing the portal through a browser.

### 3. Attendance Update Notifications *(Experimental)*

The app can notify students if their attendance changes.

* Periodically checks for updates.
* Sends a notification when a difference in attendance is detected.
* This feature is currently **experimental and not fully tested**.

### 4. Anonymous Peer Reviews

MT App allows students to send anonymous feedback or reviews to other students.

* Reviews can be submitted **without revealing the sender’s identity**.
* Reviews are **temporarily stored in Google Sheets**.
* When the recipient receives the review, it is **automatically deleted** from storage.

This system ensures:

* Temporary storage only
* No long-term review history
* Sender anonymity

## Architecture Overview

User Device
↓
College Website API (same requests as browser)

For reviews:

User Device
↓
Google Sheets (temporary storage)
↓
Recipient Device
↓
Review deleted after delivery

## Disclaimer

* MT App is an **independent student project** and is **not affiliated with or endorsed by the college**.
* The application only automates actions that students can already perform through the official website.
* The developer does **not store, sell, or process user credentials or personal information**.

## Known Limitations

* Attendance notifications are still under testing.
* If the college website changes its API or structure, the app may stop working until updated.

## Contribution

Suggestions, improvements, and bug reports are welcome.

## License

This project is provided for educational and personal use.
