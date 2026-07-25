# This module is used as a bridge between Python scripts and the Android App
_cookie_provider = None
_callback = None

def get_cookies(url):
    """Returns the cookie string for a given URL from the Android CookieManager."""
    if _cookie_provider:
        return _cookie_provider.getCookies(url)
    return None

def open_webview(url):
    """Triggers a WebView in the app for the given URL (e.g., for login)."""
    if _cookie_provider:
        _cookie_provider.openWebview(url)
    else:
        print(f"OPEN WEBVIEW REQUESTED: {url}")

def get_network_logs():
    """Returns a list of captured network requests and responses as JSON strings."""
    if _cookie_provider:
        return _cookie_provider.getNetworkLogs()
    return []

def enable_network_logging(enabled):
    """Enables or disables network interception in WebViews."""
    if _cookie_provider:
        _cookie_provider.enableNetworkLogging(enabled)

def update_progress(step, total, message):
    """Updates the progress dialog in the Android UI."""
    if _callback:
        _callback.onStepUpdate(step, total, message)
    else:
        print(f"PROGRESS: {step}/{total} - {message}")
