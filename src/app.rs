use leptos::prelude::*;
use leptos::task::spawn_local;
use leptos_meta::{provide_meta_context, MetaTags, Stylesheet, Title};
use pulldown_cmark::{Parser, Options, html};
use serde::{Deserialize, Serialize};

/// Convert markdown text to HTML
fn markdown_to_html(text: &str) -> String {
    let mut options = Options::empty();
    options.insert(Options::ENABLE_STRIKETHROUGH);
    options.insert(Options::ENABLE_TABLES);
    options.insert(Options::ENABLE_TASKLISTS);

    let parser = Parser::new_ext(text, options);
    let mut html_output = String::new();
    html::push_html(&mut html_output, parser);
    html_output
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct StatusResponse {
    pub running: bool,
    pub models: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CloudLoginResponse {
    pub success: bool,
    pub message: String,
    pub api_key: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CloudModel {
    pub name: String,
    pub display_name: String,
    pub description: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CloudModelsResponse {
    pub models: Vec<CloudModel>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ChatMessage {
    pub role: String,
    pub text: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct BraveSearchResult {
    pub title: String,
    pub url: String,
    pub description: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct BraveSearchResponse {
    pub success: bool,
    pub results: Vec<BraveSearchResult>,
    pub error: Option<String>,
}

#[server]
pub async fn brave_search(query: String, api_token: String) -> Result<BraveSearchResponse, ServerFnError> {
    if api_token.trim().is_empty() {
        return Ok(BraveSearchResponse {
            success: false,
            results: vec![],
            error: Some("API token is required".to_string()),
        });
    }

    let client = reqwest::Client::new();
    let res = client
        .get("https://api.search.brave.com/res/v1/web/search")
        .header("X-Subscription-Token", api_token.trim())
        .header("Accept", "application/json")
        .query(&[("q", query.as_str()), ("count", "5")])
        .send()
        .await;

    match res {
        Ok(response) => {
            if response.status().is_success() {
                if let Ok(json) = response.json::<serde_json::Value>().await {
                    let results: Vec<BraveSearchResult> = json["web"]["results"]
                        .as_array()
                        .map(|arr| {
                            arr.iter()
                                .take(5)
                                .filter_map(|r| {
                                    Some(BraveSearchResult {
                                        title: r["title"].as_str()?.to_string(),
                                        url: r["url"].as_str()?.to_string(),
                                        description: r["description"].as_str().unwrap_or("").to_string(),
                                    })
                                })
                                .collect()
                        })
                        .unwrap_or_default();

                    return Ok(BraveSearchResponse {
                        success: true,
                        results,
                        error: None,
                    });
                }
            } else {
                let status = response.status();
                let error_msg = if status.as_u16() == 401 {
                    "Invalid API token".to_string()
                } else if status.as_u16() == 429 {
                    "Rate limit exceeded".to_string()
                } else {
                    format!("API error: {}", status)
                };
                return Ok(BraveSearchResponse {
                    success: false,
                    results: vec![],
                    error: Some(error_msg),
                });
            }
        }
        Err(e) => {
            return Ok(BraveSearchResponse {
                success: false,
                results: vec![],
                error: Some(format!("Request failed: {}", e)),
            });
        }
    }

    Ok(BraveSearchResponse {
        success: false,
        results: vec![],
        error: Some("Unknown error".to_string()),
    })
}

#[server]
pub async fn test_brave_api(api_token: String) -> Result<BraveSearchResponse, ServerFnError> {
    brave_search("test query".to_string(), api_token).await
}

#[server]
pub async fn get_hostname() -> Result<String, ServerFnError> {
    // Try to get hostname from system
    if let Ok(hostname) = std::fs::read_to_string("/etc/hostname") {
        let hostname = hostname.trim().to_string();
        if !hostname.is_empty() {
            return Ok(hostname);
        }
    }

    // Fallback: try HOSTNAME env var
    if let Ok(hostname) = std::env::var("HOSTNAME") {
        if !hostname.is_empty() {
            return Ok(hostname);
        }
    }

    // Fallback: try running hostname command
    if let Ok(output) = std::process::Command::new("hostname").output() {
        if output.status.success() {
            let hostname = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !hostname.is_empty() {
                return Ok(hostname);
            }
        }
    }

    Ok("ollama".to_string())
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PullProgress {
    pub model: String,
    pub status: String,
    pub percent: f32,
    pub done: bool,
    pub error: Option<String>,
    pub bytes_downloaded: u64,
    pub speed: String,
    pub last_update: i64, // timestamp for speed calculation
}

// Global state for tracking pull progress (simple approach using lazy_static would be better but this works)
use std::sync::OnceLock;
use std::collections::HashMap;
use std::sync::Mutex;

static PULL_PROGRESS: OnceLock<Mutex<HashMap<String, PullProgress>>> = OnceLock::new();

fn get_progress_store() -> &'static Mutex<HashMap<String, PullProgress>> {
    PULL_PROGRESS.get_or_init(|| Mutex::new(HashMap::new()))
}

#[server]
pub async fn start_model_pull(model_name: String) -> Result<PullProgress, ServerFnError> {
    use std::process::Command;

    if model_name.trim().is_empty() {
        return Ok(PullProgress {
            model: model_name,
            status: "Error".to_string(),
            percent: 0.0,
            done: true,
            error: Some("Model name cannot be empty".to_string()),
            bytes_downloaded: 0,
            speed: "".to_string(),
            last_update: 0,
        });
    }

    // First ensure Ollama is running
    let status = get_ollama_status().await?;
    if !status.running {
        let _ = Command::new("ollama").arg("serve").spawn();
        tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
    }

    let model = model_name.trim().to_string();
    let model_clone = model.clone();

    // Initialize progress
    {
        let store = get_progress_store();
        let mut map = store.lock().unwrap();
        map.insert(model.clone(), PullProgress {
            model: model.clone(),
            status: "Starting...".to_string(),
            percent: 0.0,
            done: false,
            error: None,
            bytes_downloaded: 0,
            speed: "".to_string(),
            last_update: 0,
        });
    }

    // Start the pull using Ollama API (streams JSON progress)
    tokio::spawn(async move {
        let client = reqwest::Client::new();
        let res = client.post("http://localhost:11434/api/pull")
            .json(&serde_json::json!({ "name": model_clone }))
            .send()
            .await;

        match res {
            Ok(response) => {
                use futures::StreamExt;
                let mut stream = response.bytes_stream();

                while let Some(chunk) = stream.next().await {
                    if let Ok(bytes) = chunk {
                        let text = String::from_utf8_lossy(&bytes);
                        // Parse each line as JSON
                        for line in text.lines() {
                            if let Ok(json) = serde_json::from_str::<serde_json::Value>(line) {
                                let store = get_progress_store();
                                let mut map = store.lock().unwrap();

                                let status_text = json["status"].as_str().unwrap_or("").to_string();
                                let total = json["total"].as_u64().unwrap_or(0);
                                let completed = json["completed"].as_u64().unwrap_or(0);

                                // Get previous values to preserve if needed
                                let prev = map.get(&model_clone).cloned();
                                let prev_speed = prev.as_ref().map(|p| p.speed.clone()).unwrap_or_default();
                                let prev_percent = prev.as_ref().map(|p| p.percent).unwrap_or(0.0);

                                let percent = if total > 0 {
                                    (completed as f32 / total as f32) * 100.0
                                } else {
                                    prev_percent // Keep previous percent if no new data
                                };

                                // Calculate speed from completed bytes, keep previous if no new data
                                let speed = if total > 0 && completed > 0 {
                                    format_bytes(completed) + " / " + &format_bytes(total)
                                } else if !prev_speed.is_empty() {
                                    prev_speed // Keep previous speed
                                } else {
                                    "".to_string()
                                };

                                let is_done = status_text == "success" || json.get("error").is_some();
                                let error = json["error"].as_str().map(|s| s.to_string());

                                map.insert(model_clone.clone(), PullProgress {
                                    model: model_clone.clone(),
                                    status: if is_done && error.is_none() { "Complete".to_string() } else { status_text },
                                    percent: if is_done && error.is_none() { 100.0 } else { percent },
                                    done: is_done,
                                    error,
                                    bytes_downloaded: completed,
                                    speed,
                                    last_update: std::time::SystemTime::now()
                                        .duration_since(std::time::UNIX_EPOCH)
                                        .unwrap_or_default()
                                        .as_secs() as i64,
                                });
                            }
                        }
                    }
                }
            }
            Err(e) => {
                let store = get_progress_store();
                let mut map = store.lock().unwrap();
                map.insert(model_clone.clone(), PullProgress {
                    model: model_clone,
                    status: "Error".to_string(),
                    percent: 0.0,
                    done: true,
                    error: Some(e.to_string()),
                    bytes_downloaded: 0,
                    speed: "".to_string(),
                    last_update: 0,
                });
            }
        }
    });

    Ok(PullProgress {
        model: model_name.trim().to_string(),
        status: "Starting...".to_string(),
        percent: 0.0,
        done: false,
        error: None,
        bytes_downloaded: 0,
        speed: "".to_string(),
        last_update: 0,
    })
}

fn format_bytes(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    const GB: u64 = MB * 1024;

    if bytes >= GB {
        format!("{:.1} GB", bytes as f64 / GB as f64)
    } else if bytes >= MB {
        format!("{:.1} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{:.1} KB", bytes as f64 / KB as f64)
    } else {
        format!("{} B", bytes)
    }
}

#[server]
pub async fn cancel_model_pull(model_name: String) -> Result<bool, ServerFnError> {
    use std::process::Command;

    let model = model_name.trim().to_string();

    // Mark as cancelled in progress store
    {
        let store = get_progress_store();
        let mut map = store.lock().unwrap();
        if let Some(progress) = map.get_mut(&model) {
            progress.done = true;
            progress.status = "Cancelled".to_string();
            progress.error = Some("Download cancelled by user".to_string());
        }
    }

    // Kill any running ollama pull process for this model
    let _ = Command::new("pkill")
        .args(["-f", &format!("ollama pull {}", model)])
        .output();

    Ok(true)
}

#[server]
pub async fn check_pull_progress(model_name: String) -> Result<PullProgress, ServerFnError> {
    let model = model_name.trim().to_string();

    // Check progress store first
    {
        let store = get_progress_store();
        let map = store.lock().unwrap();
        if let Some(progress) = map.get(&model) {
            return Ok(progress.clone());
        }
    }

    // Fallback: check if model exists (might have been pulled before tracking)
    let status = get_ollama_status().await?;
    let model_exists = status.models.iter().any(|m| {
        m.starts_with(&model) || m.contains(&model)
    });

    if model_exists {
        Ok(PullProgress {
            model,
            status: "Complete".to_string(),
            percent: 100.0,
            done: true,
            error: None,
            bytes_downloaded: 0,
            speed: "".to_string(),
            last_update: 0,
        })
    } else {
        Ok(PullProgress {
            model,
            status: "Waiting...".to_string(),
            percent: 0.0,
            done: false,
            error: None,
            bytes_downloaded: 0,
            speed: "".to_string(),
            last_update: 0,
        })
    }
}

#[server]
pub async fn delete_model(model_name: String) -> Result<bool, ServerFnError> {
    use std::process::Command;

    if model_name.trim().is_empty() {
        return Ok(false);
    }

    let output = Command::new("ollama")
        .args(["rm", model_name.trim()])
        .output();

    match output {
        Ok(out) => Ok(out.status.success()),
        Err(_) => Ok(false),
    }
}

#[server]
pub async fn get_ollama_status() -> Result<StatusResponse, ServerFnError> {
    let client = reqwest::Client::new();

    // Check if Ollama is running by hitting the tags endpoint
    let res = client.get("http://localhost:11434/api/tags").send().await;

    match res {
        Ok(response) => {
            if let Ok(json) = response.json::<serde_json::Value>().await {
                let models: Vec<String> = json["models"]
                    .as_array()
                    .map(|arr| {
                        arr.iter()
                            .filter_map(|m| m["name"].as_str().map(|s| s.to_string()))
                            .collect()
                    })
                    .unwrap_or_default();
                Ok(StatusResponse { running: true, models })
            } else {
                Ok(StatusResponse { running: true, models: vec![] })
            }
        }
        Err(_) => Ok(StatusResponse { running: false, models: vec![] }),
    }
}

#[server]
pub async fn toggle_ollama_service() -> Result<StatusResponse, ServerFnError> {
    use std::process::Command;

    // Check current status
    let current = get_ollama_status().await?;

    if current.running {
        // Stop Ollama - try pkill first, then killall
        let _ = Command::new("pkill")
            .args(["-f", "ollama serve"])
            .output();

        // Give it a moment to stop
        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
    } else {
        // Start Ollama serve in background
        let _ = Command::new("ollama")
            .arg("serve")
            .spawn();

        // Give it a moment to start
        tokio::time::sleep(tokio::time::Duration::from_millis(1000)).await;
    }

    // Return new status
    get_ollama_status().await
}

// Cloud credentials storage
static CLOUD_CREDENTIALS: OnceLock<Mutex<Option<(String, String)>>> = OnceLock::new();

fn get_cloud_credentials_store() -> &'static Mutex<Option<(String, String)>> {
    CLOUD_CREDENTIALS.get_or_init(|| Mutex::new(None))
}

#[server]
pub async fn cloud_oauth_login(provider: String) -> Result<CloudLoginResponse, ServerFnError> {
    // Validate provider
    if provider != "google" && provider != "github" && provider != "email" {
        return Ok(CloudLoginResponse {
            success: false,
            message: "Invalid login provider".to_string(),
            api_key: None,
        });
    }

    // For demo purposes, simulate successful login
    // TODO: Replace with actual Ollama Cloud OAuth/auth flow
    let demo_user = match provider.as_str() {
        "google" => "user@gmail.com",
        "github" => "github_user",
        "email" => "user@example.com",
        _ => "demo_user",
    };

    let store = get_cloud_credentials_store();
    let mut creds = store.lock().unwrap();
    *creds = Some((demo_user.to_string(), "demo_key".to_string()));

    Ok(CloudLoginResponse {
        success: true,
        message: "Connected (demo mode)".to_string(),
        api_key: Some(demo_user.to_string()),
    })
}

#[server]
pub async fn cloud_email_login(email: String, password: String) -> Result<CloudLoginResponse, ServerFnError> {
    // Validate input
    if email.trim().is_empty() || password.trim().is_empty() {
        return Ok(CloudLoginResponse {
            success: false,
            message: "Email and password are required".to_string(),
            api_key: None,
        });
    }

    // For demo purposes, simulate successful login
    // TODO: Replace with actual Ollama Cloud authentication
    let store = get_cloud_credentials_store();
    let mut creds = store.lock().unwrap();
    *creds = Some((email.trim().to_string(), "demo_key".to_string()));

    Ok(CloudLoginResponse {
        success: true,
        message: "Connected (demo mode)".to_string(),
        api_key: Some(email.trim().to_string()),
    })
}

#[server]
pub async fn cloud_logout() -> Result<bool, ServerFnError> {
    let store = get_cloud_credentials_store();
    let mut creds = store.lock().unwrap();
    *creds = None;
    Ok(true)
}

#[server]
pub async fn check_cloud_login() -> Result<Option<String>, ServerFnError> {
    let store = get_cloud_credentials_store();
    let creds = store.lock().unwrap();
    Ok(creds.as_ref().map(|(email, _)| email.clone()))
}

#[server]
pub async fn get_cloud_models() -> Result<CloudModelsResponse, ServerFnError> {
    // Check if logged in and get API key in a separate scope to release lock
    let api_key = {
        let store = get_cloud_credentials_store();
        let creds = store.lock().unwrap();
        match creds.as_ref() {
            Some((_, key)) => key.clone(),
            None => return Ok(CloudModelsResponse { models: vec![] }),
        }
    };

    // Try to fetch cloud models
    let client = reqwest::Client::new();
    let res = client.get("https://api.ollama.com/v1/models")
        .header("Authorization", format!("Bearer {}", api_key))
        .send()
        .await;

    match res {
        Ok(response) => {
            if let Ok(json) = response.json::<serde_json::Value>().await {
                let models: Vec<CloudModel> = json["models"]
                    .as_array()
                    .map(|arr| {
                        arr.iter()
                            .filter_map(|m| {
                                Some(CloudModel {
                                    name: m["name"].as_str()?.to_string(),
                                    display_name: m["display_name"].as_str()
                                        .unwrap_or(m["name"].as_str()?)
                                        .to_string(),
                                    description: m["description"].as_str()
                                        .unwrap_or("")
                                        .to_string(),
                                })
                            })
                            .collect()
                    })
                    .unwrap_or_default();

                return Ok(CloudModelsResponse { models });
            }
        }
        Err(_) => {}
    }

    // Return demo models when cloud is unavailable
    Ok(CloudModelsResponse {
        models: vec![
            CloudModel {
                name: "gpt-4-turbo".to_string(),
                display_name: "GPT-4 Turbo".to_string(),
                description: "Most capable GPT-4 model".to_string(),
            },
            CloudModel {
                name: "claude-3-opus".to_string(),
                display_name: "Claude 3 Opus".to_string(),
                description: "Most intelligent Claude model".to_string(),
            },
            CloudModel {
                name: "claude-3-sonnet".to_string(),
                display_name: "Claude 3 Sonnet".to_string(),
                description: "Balanced performance and speed".to_string(),
            },
            CloudModel {
                name: "gemini-pro".to_string(),
                display_name: "Gemini Pro".to_string(),
                description: "Google's advanced model".to_string(),
            },
        ],
    })
}

pub fn shell(options: LeptosOptions) -> impl IntoView {
    view! {
        <!DOCTYPE html>
        <html lang="en">
            <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"/>
                <AutoReload options=options.clone() />
                <HydrationScripts options/>
                <MetaTags/>
            </head>
            <body>
                <App/>
            </body>
        </html>
    }
}

#[component]
pub fn App() -> impl IntoView {
    provide_meta_context();

    // State
    let (input, set_input) = signal(String::new());
    let (messages, set_messages) = signal(Vec::<ChatMessage>::new());
    let (selected_model, set_selected_model) = signal::<Option<String>>(None);
    let (is_streaming, set_is_streaming) = signal(false);
    let (menu_open, set_menu_open) = signal(false);
    let (models_panel_open, set_models_panel_open) = signal(false);
    let (ollama_running, set_ollama_running) = signal(false);
    let (toggle_pending, set_toggle_pending) = signal(false);
    let (show_add_model, set_show_add_model) = signal(false);
    let (new_model_name, set_new_model_name) = signal(String::new());
    let (active_downloads, set_active_downloads) = signal::<Vec<PullProgress>>(vec![]);
    let (deleting_model, set_deleting_model) = signal::<Option<String>>(None);
    let (status_dropdown_open, set_status_dropdown_open) = signal(false);
    let (current_theme, set_current_theme) = signal(String::from("light"));

    // Brave Search state
    let (brave_search_enabled, set_brave_search_enabled) = signal(false);
    let (brave_api_token, set_brave_api_token) = signal(String::new());
    let (brave_submenu_open, set_brave_submenu_open) = signal(false);
    let (brave_test_status, set_brave_test_status) = signal::<Option<String>>(None);
    let (brave_test_pending, set_brave_test_pending) = signal(false);

    // Cloud state
    let (cloud_panel_open, set_cloud_panel_open) = signal(false);
    let (cloud_logged_in, set_cloud_logged_in) = signal(false);
    let (cloud_login_pending, set_cloud_login_pending) = signal(false);
    let (cloud_login_error, set_cloud_login_error) = signal::<Option<String>>(None);
    let (cloud_user_email, set_cloud_user_email) = signal::<Option<String>>(None);
    let (show_email_login, set_show_email_login) = signal(false);
    let (cloud_email, set_cloud_email) = signal(String::new());
    let (cloud_password, set_cloud_password) = signal(String::new());
    let (show_add_cloud_model, set_show_add_cloud_model) = signal(false);
    let (new_cloud_model_name, set_new_cloud_model_name) = signal(String::new());

    // Load theme and Brave Search settings from localStorage on mount
    #[cfg(target_arch = "wasm32")]
    {
        use wasm_bindgen::JsCast;
        Effect::new(move |_| {
            if let Some(window) = web_sys::window() {
                if let Ok(Some(storage)) = window.local_storage() {
                    // Load theme
                    if let Ok(Some(saved_theme)) = storage.get_item("theme") {
                        set_current_theme.set(saved_theme.clone());
                        if let Some(document) = window.document() {
                            if let Some(body) = document.body() {
                                let _ = body.set_attribute("data-theme", &saved_theme);
                            }
                        }
                    }
                    // Load Brave Search settings
                    if let Ok(Some(enabled)) = storage.get_item("brave_search_enabled") {
                        set_brave_search_enabled.set(enabled == "true");
                    }
                    if let Ok(Some(token)) = storage.get_item("brave_api_token") {
                        set_brave_api_token.set(token);
                    }
                    // Load last selected model
                    if let Ok(Some(saved_model)) = storage.get_item("selected_model") {
                        if !saved_model.is_empty() {
                            set_selected_model.set(Some(saved_model));
                        }
                    }
                }
            }
        });
    }

    // Apply theme change
    let apply_theme = move |theme: String| {
        set_current_theme.set(theme.clone());
        #[cfg(target_arch = "wasm32")]
        {
            if let Some(window) = web_sys::window() {
                if let Ok(Some(storage)) = window.local_storage() {
                    let _ = storage.set_item("theme", &theme);
                }
                if let Some(document) = window.document() {
                    if let Some(body) = document.body() {
                        let _ = body.set_attribute("data-theme", &theme);
                    }
                }
            }
        }
    };

    // Resources
    let status_resource = Resource::new(|| (), |_| get_ollama_status());
    let hostname_resource = Resource::new(|| (), |_| get_hostname());
    let cloud_login_resource = Resource::new(|| (), |_| check_cloud_login());
    let cloud_models_resource = Resource::new(
        move || cloud_logged_in.get(),
        |logged_in| async move {
            if logged_in {
                get_cloud_models().await
            } else {
                Ok(CloudModelsResponse { models: vec![] })
            }
        }
    );

    // Toggle action
    let toggle_action = Action::new(move |_: &()| async move {
        toggle_ollama_service().await
    });

    // Delete model action
    let do_delete_model = move |model_name: String| {
        if model_name.trim().is_empty() {
            return;
        }

        set_deleting_model.set(Some(model_name.clone()));

        let model = model_name.clone();
        spawn_local(async move {
            if let Ok(success) = delete_model(model.clone()).await {
                if success {
                    // Clear selected model if it was deleted
                    if selected_model.get().as_ref() == Some(&model) {
                        set_selected_model.set(None);
                    }
                    // Refresh models list
                    status_resource.refetch();
                }
            }
            set_deleting_model.set(None);
        });
    };

    // Start download action
    let start_download = move |model_name: String| {
        if model_name.trim().is_empty() {
            return;
        }

        // Check if already downloading
        let downloads = active_downloads.get();
        if downloads.iter().any(|d| d.model == model_name.trim() && !d.done) {
            return;
        }

        // Add to active downloads
        set_active_downloads.update(|downloads| {
            downloads.push(PullProgress {
                model: model_name.trim().to_string(),
                status: "Starting...".to_string(),
                percent: 0.0,
                done: false,
                error: None,
                bytes_downloaded: 0,
                speed: "".to_string(),
                last_update: 0,
            });
        });

        // Start the pull
        let model = model_name.trim().to_string();
        spawn_local(async move {
            let _ = start_model_pull(model).await;
        });

        // Clear input
        set_new_model_name.set(String::new());
        set_show_add_model.set(false);
    };

    // Poll for download progress
    #[cfg(target_arch = "wasm32")]
    {
        use wasm_bindgen::prelude::*;

        let check_progress = move || {
            let downloads = active_downloads.get();
            let pending: Vec<_> = downloads.iter()
                .filter(|d| !d.done)
                .map(|d| d.model.clone())
                .collect();

            for model in pending {
                let model_clone = model.clone();
                spawn_local(async move {
                    if let Ok(progress) = check_pull_progress(model_clone.clone()).await {
                        let is_complete = progress.done && progress.error.is_none();

                        set_active_downloads.update(|downloads| {
                            if let Some(d) = downloads.iter_mut().find(|d| d.model == model_clone) {
                                // Calculate download speed
                                let now = js_sys::Date::now() as i64;
                                let time_diff = if d.last_update > 0 { (now - d.last_update) / 1000 } else { 0 };
                                let percent_diff = progress.percent - d.percent;
                                
                                // Estimate speed based on percent change (rough estimate)
                                let speed_str = if time_diff > 0 && percent_diff > 0.0 {
                                    // Assume models are roughly 4GB for estimation
                                    let estimated_bytes = (percent_diff / 100.0) * 4_000_000_000.0;
                                    let bytes_per_sec = estimated_bytes / (time_diff as f32);
                                    if bytes_per_sec > 1_000_000_000.0 {
                                        format!("{:.1} GB/s", bytes_per_sec / 1_000_000_000.0)
                                    } else if bytes_per_sec > 1_000_000.0 {
                                        format!("{:.1} MB/s", bytes_per_sec / 1_000_000.0)
                                    } else if bytes_per_sec > 1_000.0 {
                                        format!("{:.1} KB/s", bytes_per_sec / 1_000.0)
                                    } else {
                                        format!("{:.0} B/s", bytes_per_sec)
                                    }
                                } else {
                                    "".to_string()
                                };

                                d.status = progress.status;
                                d.percent = progress.percent;
                                d.done = progress.done;
                                d.error = progress.error;
                                d.speed = speed_str;
                                d.last_update = now;
                            }
                        });

                        // Refresh models list when complete
                        if is_complete {
                            status_resource.refetch();
                        }
                    }
                });
            }
        };

        // Set up interval to check progress
        Effect::new(move |_| {
            let downloads = active_downloads.get();
            if downloads.iter().any(|d| !d.done) {
                let cb = Closure::wrap(Box::new(move || {
                    check_progress();
                }) as Box<dyn Fn()>);

                if let Some(window) = web_sys::window() {
                    let _ = window.set_timeout_with_callback_and_timeout_and_arguments_0(
                        cb.as_ref().unchecked_ref(),
                        2000, // Check every 2 seconds
                    );
                }
                cb.forget();
            }
        });
    }

    // Update running state when status loads
    Effect::new(move |_| {
        if let Some(Ok(status)) = status_resource.get() {
            set_ollama_running.set(status.running);
        }
    });

    // Update running state when toggle completes
    Effect::new(move |_| {
        if let Some(Ok(status)) = toggle_action.value().get() {
            set_ollama_running.set(status.running);
            set_toggle_pending.set(false);
            // Refetch models after toggle
            status_resource.refetch();
        }
    });

    // Auto-select model when status loads (respect saved preference or pick first)
    Effect::new(move |_| {
        if let Some(Ok(status)) = status_resource.get() {
            if !status.models.is_empty() {
                let current = selected_model.get();
                // If no model selected, or selected model no longer exists, pick one
                let should_select = match &current {
                    None => true,
                    Some(model) => !status.models.iter().any(|m| m == model),
                };
                if should_select {
                    set_selected_model.set(Some(status.models[0].clone()));
                }
            }
        }
    });

    // Check cloud login status on load
    Effect::new(move |_| {
        if let Some(Ok(email_opt)) = cloud_login_resource.get() {
            if let Some(email) = email_opt {
                set_cloud_logged_in.set(true);
                set_cloud_user_email.set(Some(email));
            }
        }
    });

    // Auto-focus input on mount and after streaming ends
    #[cfg(target_arch = "wasm32")]
    {
        use wasm_bindgen::JsCast;

        // Focus on mount
        Effect::new(move |_| {
            if let Some(window) = web_sys::window() {
                if let Some(document) = window.document() {
                    if let Some(input) = document.get_element_by_id("prompt-input") {
                        if let Some(textarea) = input.dyn_ref::<web_sys::HtmlTextAreaElement>() {
                            let _ = textarea.focus();
                        }
                    }
                }
            }
        });

        // Re-focus when streaming ends
        Effect::new(move |_| {
            let streaming = is_streaming.get();
            if !streaming {
                // Small delay to ensure DOM is ready
                if let Some(window) = web_sys::window() {
                    let cb = wasm_bindgen::closure::Closure::wrap(Box::new(move || {
                        if let Some(window) = web_sys::window() {
                            if let Some(document) = window.document() {
                                if let Some(input) = document.get_element_by_id("prompt-input") {
                                    if let Some(textarea) = input.dyn_ref::<web_sys::HtmlTextAreaElement>() {
                                        let _ = textarea.focus();
                                    }
                                }
                            }
                        }
                    }) as Box<dyn Fn()>);
                    let _ = window.set_timeout_with_callback_and_timeout_and_arguments_0(
                        cb.as_ref().unchecked_ref(),
                        100,
                    );
                    cb.forget();
                }
            }
        });
    }

    // OAuth login handler
    let do_oauth_login = move |provider: String| {
        set_cloud_login_pending.set(true);
        set_cloud_login_error.set(None);

        spawn_local(async move {
            match cloud_oauth_login(provider.clone()).await {
                Ok(response) => {
                    if response.success {
                        set_cloud_logged_in.set(true);
                        set_cloud_user_email.set(response.api_key);
                        set_show_email_login.set(false);
                        cloud_models_resource.refetch();
                    } else {
                        set_cloud_login_error.set(Some(response.message));
                    }
                }
                Err(e) => {
                    set_cloud_login_error.set(Some(format!("Error: {}", e)));
                }
            }
            set_cloud_login_pending.set(false);
        });
    };

    // Email login handler
    let do_email_login = move || {
        let email = cloud_email.get();
        let password = cloud_password.get();

        if email.trim().is_empty() || password.trim().is_empty() {
            set_cloud_login_error.set(Some("Please enter email and password".to_string()));
            return;
        }

        set_cloud_login_pending.set(true);
        set_cloud_login_error.set(None);

        spawn_local(async move {
            match cloud_email_login(email.clone(), password).await {
                Ok(response) => {
                    if response.success {
                        set_cloud_logged_in.set(true);
                        set_cloud_user_email.set(Some(email));
                        set_cloud_email.set(String::new());
                        set_cloud_password.set(String::new());
                        set_show_email_login.set(false);
                        cloud_models_resource.refetch();
                    } else {
                        set_cloud_login_error.set(Some(response.message));
                    }
                }
                Err(e) => {
                    set_cloud_login_error.set(Some(format!("Error: {}", e)));
                }
            }
            set_cloud_login_pending.set(false);
        });
    };

    // Cloud logout handler
    let do_cloud_logout = move || {
        spawn_local(async move {
            let _ = cloud_logout().await;
            set_cloud_logged_in.set(false);
            set_cloud_user_email.set(None);
        });
    };

    // Auto-scroll chat window when messages change
    #[cfg(target_arch = "wasm32")]
    Effect::new(move |_| {
        let _ = messages.get(); // Subscribe to messages changes
        // Use requestAnimationFrame to ensure DOM is updated before scrolling
        if let Some(window) = web_sys::window() {
            use wasm_bindgen::prelude::*;
            use wasm_bindgen::JsCast;
            let cb = Closure::once(Box::new(move || {
                if let Some(window) = web_sys::window() {
                    if let Some(document) = window.document() {
                        if let Some(chat_window) = document.get_element_by_id("chat-window") {
                            chat_window.set_scroll_top(chat_window.scroll_height());
                        }
                    }
                }
            }) as Box<dyn FnOnce()>);
            let _ = window.request_animation_frame(cb.as_ref().unchecked_ref());
            cb.forget();
        }
    });

    // Send message handler
    let do_send = move || {
        let text = input.get();
        if text.trim().is_empty() || selected_model.get().is_none() || is_streaming.get() {
            return;
        }

        // Add user message
        set_messages.update(|msgs| {
            msgs.push(ChatMessage {
                role: "user".to_string(),
                text: text.clone(),
            });
        });

        // Add placeholder AI message
        set_messages.update(|msgs| {
            msgs.push(ChatMessage {
                role: "ai".to_string(),
                text: "".to_string(),
            });
        });

        set_input.set(String::new());
        set_is_streaming.set(true);

        // Start streaming
        let model = selected_model.get().unwrap();
        let user_query = text.clone();
        let search_enabled = brave_search_enabled.get();
        let api_token = brave_api_token.get();

        #[cfg(target_arch = "wasm32")]
        {
            use wasm_bindgen::prelude::*;
            use wasm_bindgen::JsCast;

            // Use fetch with SSE
            wasm_bindgen_futures::spawn_local(async move {
                let window = web_sys::window().unwrap();

                // Build the prompt - optionally with search results
                let prompt = if search_enabled && !api_token.trim().is_empty() {
                    // First, perform web search
                    match brave_search(user_query.clone(), api_token).await {
                        Ok(search_response) if search_response.success && !search_response.results.is_empty() => {
                            // Build context from search results
                            let mut context = String::from("I searched the web for your question. Here are the relevant results:\n\n");
                            for (i, result) in search_response.results.iter().enumerate() {
                                context.push_str(&format!(
                                    "{}. **{}**\n   URL: {}\n   {}\n\n",
                                    i + 1,
                                    result.title,
                                    result.url,
                                    result.description
                                ));
                            }
                            context.push_str(&format!(
                                "---\nBased on the above web search results, please answer the following question:\n\n{}",
                                user_query
                            ));
                            context
                        }
                        _ => user_query.clone() // Fall back to original query if search fails
                    }
                } else {
                    user_query.clone()
                };

                let opts = web_sys::RequestInit::new();
                opts.set_method("POST");
                opts.set_body(&JsValue::from_str(&serde_json::json!({
                    "model": model,
                    "prompt": prompt
                }).to_string()));

                let headers = web_sys::Headers::new().unwrap();
                headers.set("Content-Type", "application/json").unwrap();
                opts.set_headers(&headers);

                let request = web_sys::Request::new_with_str_and_init("/api/stream", &opts).unwrap();

                let resp_value = wasm_bindgen_futures::JsFuture::from(window.fetch_with_request(&request)).await;

                if let Ok(resp) = resp_value {
                    let resp: web_sys::Response = resp.dyn_into().unwrap();
                    if let Some(body) = resp.body() {
                        let reader: web_sys::ReadableStreamDefaultReader = body.get_reader().unchecked_into();

                        let mut full_text = String::new();

                        loop {
                            let read_promise = reader.read();
                            let result = wasm_bindgen_futures::JsFuture::from(read_promise).await;
                            if let Ok(chunk) = result {
                                let done = js_sys::Reflect::get(&chunk, &JsValue::from_str("done")).unwrap();

                                if done.as_bool().unwrap_or(true) {
                                    break;
                                }

                                let value = js_sys::Reflect::get(&chunk, &JsValue::from_str("value")).unwrap();
                                let array: js_sys::Uint8Array = value.dyn_into().unwrap();
                                let bytes = array.to_vec();
                                let text = String::from_utf8_lossy(&bytes);

                                // Parse SSE format
                                for line in text.lines() {
                                    if line.starts_with("data:") {
                                        let data = line.trim_start_matches("data:").trim();
                                        if data == "__END__" || data.is_empty() {
                                            if data == "__END__" {
                                                set_is_streaming.set(false);
                                            }
                                            break;
                                        }
                                        full_text.push_str(data);
                                        full_text.push(' '); // Add space between chunks

                                        let current_text = full_text.clone();
                                        set_messages.update(|msgs| {
                                            if let Some(last) = msgs.last_mut() {
                                                if last.role == "ai" {
                                                    last.text = current_text;
                                                }
                                            }
                                        });
                                    }
                                }
                            } else {
                                break;
                            }
                        }
                    }
                }
                set_is_streaming.set(false);
            });
        }
    };

    // Close all menus
    let close_menus = move || {
        set_menu_open.set(false);
        set_models_panel_open.set(false);
        set_cloud_panel_open.set(false);
    };

    // Toggle menu
    let toggle_menu = move |ev: web_sys::MouseEvent| {
        ev.stop_propagation();
        if menu_open.get() {
            close_menus();
        } else {
            set_menu_open.set(true);
        }
    };

    // Select model and persist to localStorage
    let select_model = move |model: String| {
        set_selected_model.set(Some(model.clone()));
        #[cfg(target_arch = "wasm32")]
        {
            if let Some(window) = web_sys::window() {
                if let Ok(Some(storage)) = window.local_storage() {
                    let _ = storage.set_item("selected_model", &model);
                }
            }
        }
        close_menus();
    };

    // Handle runner item interaction (hover/click)
    let open_models_panel = move |ev: web_sys::MouseEvent| {
        ev.stop_propagation();
        set_models_panel_open.set(true);
    };

    view! {
        <Stylesheet id="leptos" href="/pkg/ollama-rust.css"/>
        <Title text="Ollama Rust"/>

        // Backdrop to close menus when clicking outside
        <div class="menu-backdrop"
             class:hidden=move || !menu_open.get()
             on:click=move |_| close_menus()
             on:touchend=move |_| close_menus()>
        </div>

        <div class="chat-container">
            // Header
            <div class="chat-header">
                <div class="header-left">
                    <div class="model-dropdown">
                        <button id="model-button" type="button" on:click=toggle_menu>
                            {move || {
                                if let Some(model) = selected_model.get() {
                                    // Truncate long model names
                                    let display = if model.len() > 15 {
                                        format!("{}...", &model[..12])
                                    } else {
                                        model
                                    };
                                    format!("🧠 {}", display)
                                } else {
                                    "🧠 Model".to_string()
                                }
                            }}
                        </button>

                        <div id="model-menu"
                             class="model-menu"
                             class:hidden=move || !menu_open.get()
                             on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()>
                            <div class="runner-list">
                                <div class="runner-item"
                                     on:mouseenter=open_models_panel
                                     on:click=open_models_panel
                                     on:touchstart=move |ev: web_sys::TouchEvent| {
                                         ev.stop_propagation();
                                         set_models_panel_open.set(true);
                                     }>
                                    <div class="runner-name">"ollama local"</div>

                                    <div id="models-panel"
                                         class="models-panel"
                                         class:hidden=move || !models_panel_open.get()
                                         on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()>
                                        // Add Model section
                                        <div class="add-model-section">
                                            // Library link
                                            <a href="https://ollama.com/library"
                                               target="_blank"
                                               rel="noopener noreferrer"
                                               class="model-option library-link"
                                               on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()>
                                                "📚 Browse Models"
                                            </a>

                                            {move || if show_add_model.get() {
                                                view! {
                                                    <div class="add-model-input-row">
                                                        <input
                                                            type="text"
                                                            class="add-model-input"
                                                            placeholder="model name (e.g. llama3)"
                                                            prop:value=move || new_model_name.get()
                                                            on:input=move |ev| set_new_model_name.set(event_target_value(&ev))
                                                            on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()
                                                            on:keydown=move |ev: web_sys::KeyboardEvent| {
                                                                ev.stop_propagation();
                                                                if ev.key() == "Enter" {
                                                                    let name = new_model_name.get();
                                                                    start_download(name);
                                                                }
                                                            }
                                                        />
                                                        <button
                                                            class="add-model-btn pull-btn"
                                                            on:click=move |ev: web_sys::MouseEvent| {
                                                                ev.stop_propagation();
                                                                let name = new_model_name.get();
                                                                start_download(name);
                                                            }
                                                        >
                                                            "Pull"
                                                        </button>
                                                        <button
                                                            class="add-model-btn cancel-btn"
                                                            on:click=move |ev: web_sys::MouseEvent| {
                                                                ev.stop_propagation();
                                                                set_show_add_model.set(false);
                                                                set_new_model_name.set(String::new());
                                                            }
                                                        >
                                                            "✕"
                                                        </button>
                                                    </div>
                                                }.into_any()
                                            } else {
                                                view! {
                                                    <div class="model-option add-model-option"
                                                         on:click=move |ev: web_sys::MouseEvent| {
                                                             ev.stop_propagation();
                                                             set_show_add_model.set(true);
                                                         }>
                                                        "+ Add Model"
                                                    </div>
                                                }.into_any()
                                            }}
                                        </div>

                                        // Divider
                                        <div class="model-divider"></div>

                                        // Models list
                                        <Suspense fallback=move || view! { <div class="loading-models">"Loading..."</div> }>
                                            {move || {
                                                status_resource.get().map(|result| {
                                                    match result {
                                                        Ok(status) => {
                                                            if status.models.is_empty() {
                                                                view! {
                                                                    <div class="no-models">"Turn on Ollama to view installed models"</div>
                                                                }.into_any()
                                                            } else {
                                                                view! {
                                                                    <div id="ollama-models" class="model-submenu">
                                                                        {status.models.into_iter().map(|model| {
                                                                            let m_click = model.clone();
                                                                            let m_touch = model.clone();
                                                                            let m_display = model.clone();
                                                                            let m_delete = model.clone();
                                                                            let m_delete_for_closure = m_delete.clone();
                                                                            let is_cloud_model = model.to_lowercase().contains("cloud");
                                                                            let is_deleting = move || {
                                                                                deleting_model.get().as_ref() == Some(&m_delete_for_closure)
                                                                            };
                                                                            view! {
                                                                                <div class="model-option-row">
                                                                                    <div class="model-option"
                                                                                         on:click=move |ev: web_sys::MouseEvent| {
                                                                                             ev.stop_propagation();
                                                                                             select_model(m_click.clone());
                                                                                         }
                                                                                         on:touchend=move |ev: web_sys::TouchEvent| {
                                                                                             ev.stop_propagation();
                                                                                             select_model(m_touch.clone());
                                                                                         }>
                                                                                        {m_display}
                                                                                        {if is_cloud_model {
                                                                                            view! {
                                                                                                <span class="cloud-warning" title="Cloud models not supported at this time">"⚠️"</span>
                                                                                            }.into_any()
                                                                                        } else {
                                                                                            view! { <></> }.into_any()
                                                                                        }}
                                                                                    </div>
                                                                                    <button
                                                                                        class="model-delete-btn"
                                                                                        title="Delete model"
                                                                                        disabled=is_deleting()
                                                                                        on:click=move |ev: web_sys::MouseEvent| {
                                                                                            ev.stop_propagation();
                                                                                            do_delete_model(m_delete.clone());
                                                                                        }>
                                                                                        {if is_deleting() { "..." } else { "❌" }}
                                                                                    </button>
                                                                                </div>
                                                                            }
                                                                        }).collect_view()}
                                                                    </div>
                                                                }.into_any()
                                                            }
                                                        }
                                                        Err(_) => view! { <div class="error-models">"Error loading models"</div> }.into_any()
                                                    }
                                                })
                                            }}
                                        </Suspense>
                                    </div>
                                </div>

                                // Ollama Cloud runner item - HIDDEN (cloud not yet supported)
                                // To re-enable, remove the style="display:none"
                                <div class="runner-item cloud-runner" style="display:none"
                                     on:mouseenter=move |ev: web_sys::MouseEvent| {
                                         ev.stop_propagation();
                                         set_cloud_panel_open.set(true);
                                         set_models_panel_open.set(false);
                                     }
                                     on:mouseleave=move |ev: web_sys::MouseEvent| {
                                         ev.stop_propagation();
                                         set_cloud_panel_open.set(false);
                                     }
                                     on:click=move |ev: web_sys::MouseEvent| {
                                         ev.stop_propagation();
                                         set_cloud_panel_open.set(true);
                                         set_models_panel_open.set(false);
                                     }
                                     on:touchstart=move |ev: web_sys::TouchEvent| {
                                         ev.stop_propagation();
                                         set_cloud_panel_open.set(true);
                                         set_models_panel_open.set(false);
                                     }>
                                    <div class="runner-name">
                                        "ollama cloud"
                                        {move || if cloud_logged_in.get() {
                                            view! { <span class="cloud-badge">"●"</span> }.into_any()
                                        } else {
                                            view! { <></> }.into_any()
                                        }}
                                    </div>

                                    <div id="cloud-panel"
                                         class="models-panel cloud-panel"
                                         class:hidden=move || !cloud_panel_open.get()
                                         on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()>

                                        {move || if cloud_logged_in.get() {
                                            // Logged in view - show cloud models and logout
                                            view! {
                                                <div class="cloud-user-section">
                                                    <div class="cloud-user-info">
                                                        <span class="cloud-user-icon">"👤"</span>
                                                        <span class="cloud-user-email">
                                                            {move || cloud_user_email.get().unwrap_or_default()}
                                                        </span>
                                                    </div>
                                                    <button class="cloud-logout-btn"
                                                            on:click=move |ev: web_sys::MouseEvent| {
                                                                ev.stop_propagation();
                                                                do_cloud_logout();
                                                            }>
                                                        "Logout"
                                                    </button>
                                                </div>

                                                <div class="model-divider"></div>

                                                // Add Cloud Model section
                                                <div class="add-model-section">
                                                    <a href="https://ollama.com/library"
                                                       target="_blank"
                                                       rel="noopener noreferrer"
                                                       class="model-option library-link"
                                                       on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()>
                                                        "📚 Browse Models"
                                                    </a>

                                                    {move || if show_add_cloud_model.get() {
                                                        view! {
                                                            <div class="add-model-input-row">
                                                                <input
                                                                    type="text"
                                                                    class="add-model-input"
                                                                    placeholder="model name (e.g. llama3)"
                                                                    prop:value=move || new_cloud_model_name.get()
                                                                    on:input=move |ev| set_new_cloud_model_name.set(event_target_value(&ev))
                                                                    on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()
                                                                    on:keydown=move |ev: web_sys::KeyboardEvent| {
                                                                        ev.stop_propagation();
                                                                        if ev.key() == "Enter" {
                                                                            let name = new_cloud_model_name.get();
                                                                            if !name.trim().is_empty() {
                                                                                set_selected_model.set(Some(format!("cloud:{}", name.trim())));
                                                                                set_new_cloud_model_name.set(String::new());
                                                                                set_show_add_cloud_model.set(false);
                                                                                close_menus();
                                                                            }
                                                                        }
                                                                    }
                                                                />
                                                                <button
                                                                    class="add-model-btn pull-btn"
                                                                    on:click=move |ev: web_sys::MouseEvent| {
                                                                        ev.stop_propagation();
                                                                        let name = new_cloud_model_name.get();
                                                                        if !name.trim().is_empty() {
                                                                            set_selected_model.set(Some(format!("cloud:{}", name.trim())));
                                                                            set_new_cloud_model_name.set(String::new());
                                                                            set_show_add_cloud_model.set(false);
                                                                            close_menus();
                                                                        }
                                                                    }
                                                                >
                                                                    "Add"
                                                                </button>
                                                                <button
                                                                    class="add-model-btn cancel-btn"
                                                                    on:click=move |ev: web_sys::MouseEvent| {
                                                                        ev.stop_propagation();
                                                                        set_show_add_cloud_model.set(false);
                                                                        set_new_cloud_model_name.set(String::new());
                                                                    }
                                                                >
                                                                    "✕"
                                                                </button>
                                                            </div>
                                                        }.into_any()
                                                    } else {
                                                        view! {
                                                            <div class="model-option add-model-option"
                                                                 on:click=move |ev: web_sys::MouseEvent| {
                                                                     ev.stop_propagation();
                                                                     set_show_add_cloud_model.set(true);
                                                                 }>
                                                                "+ Add Model"
                                                            </div>
                                                        }.into_any()
                                                    }}
                                                </div>

                                                <div class="model-divider"></div>

                                                <Suspense fallback=move || view! { <div class="loading-models">"Loading cloud models..."</div> }>
                                                    {move || {
                                                        cloud_models_resource.get().map(|result| {
                                                            match result {
                                                                Ok(response) => {
                                                                    if response.models.is_empty() {
                                                                        view! {
                                                                            <div class="no-models">"No cloud models available"</div>
                                                                        }.into_any()
                                                                    } else {
                                                                        view! {
                                                                            <div class="cloud-models-list">
                                                                                {response.models.into_iter().map(|model| {
                                                                                    let m_click = model.name.clone();
                                                                                    let m_display = model.display_name.clone();
                                                                                    let m_desc = model.description.clone();
                                                                                    view! {
                                                                                        <div class="cloud-model-option"
                                                                                             on:click=move |ev: web_sys::MouseEvent| {
                                                                                                 ev.stop_propagation();
                                                                                                 set_selected_model.set(Some(format!("cloud:{}", m_click.clone())));
                                                                                                 close_menus();
                                                                                             }>
                                                                                            <div class="cloud-model-name">{m_display}</div>
                                                                                            <div class="cloud-model-desc">{m_desc}</div>
                                                                                        </div>
                                                                                    }
                                                                                }).collect_view()}
                                                                            </div>
                                                                        }.into_any()
                                                                    }
                                                                }
                                                                Err(_) => view! { <div class="error-models">"Error loading cloud models"</div> }.into_any()
                                                            }
                                                        })
                                                    }}
                                                </Suspense>
                                            }.into_any()
                                        } else {
                                            // Not logged in - show login options
                                            view! {
                                                <div class="cloud-login-section">
                                                    <div class="cloud-login-header">"Sign in to Ollama Cloud"</div>

                                                    {move || cloud_login_error.get().map(|err| {
                                                        view! {
                                                            <div class="cloud-login-error">{err}</div>
                                                        }
                                                    })}

                                                    {move || if show_email_login.get() {
                                                        // Email/password form
                                                        view! {
                                                            <input
                                                                type="email"
                                                                class="cloud-login-input"
                                                                placeholder="Email"
                                                                prop:value=move || cloud_email.get()
                                                                on:input=move |ev| set_cloud_email.set(event_target_value(&ev))
                                                                on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()
                                                                on:keydown=move |ev: web_sys::KeyboardEvent| {
                                                                    ev.stop_propagation();
                                                                    if ev.key() == "Enter" {
                                                                        do_email_login();
                                                                    }
                                                                }
                                                            />

                                                            <input
                                                                type="password"
                                                                class="cloud-login-input"
                                                                placeholder="Password"
                                                                prop:value=move || cloud_password.get()
                                                                on:input=move |ev| set_cloud_password.set(event_target_value(&ev))
                                                                on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()
                                                                on:keydown=move |ev: web_sys::KeyboardEvent| {
                                                                    ev.stop_propagation();
                                                                    if ev.key() == "Enter" {
                                                                        do_email_login();
                                                                    }
                                                                }
                                                            />

                                                            <button
                                                                class="cloud-login-btn"
                                                                disabled=move || cloud_login_pending.get()
                                                                on:click=move |ev: web_sys::MouseEvent| {
                                                                    ev.stop_propagation();
                                                                    do_email_login();
                                                                }>
                                                                {move || if cloud_login_pending.get() {
                                                                    "Signing in..."
                                                                } else {
                                                                    "Sign In"
                                                                }}
                                                            </button>

                                                            <button
                                                                class="cloud-back-btn"
                                                                on:click=move |ev: web_sys::MouseEvent| {
                                                                    ev.stop_propagation();
                                                                    set_show_email_login.set(false);
                                                                    set_cloud_login_error.set(None);
                                                                }>
                                                                "← Back to other options"
                                                            </button>
                                                        }.into_any()
                                                    } else {
                                                        // OAuth buttons
                                                        view! {
                                                            <button
                                                                class="oauth-btn google-btn"
                                                                disabled=move || cloud_login_pending.get()
                                                                on:click=move |ev: web_sys::MouseEvent| {
                                                                    ev.stop_propagation();
                                                                    do_oauth_login("google".to_string());
                                                                }>
                                                                <svg class="oauth-icon" viewBox="0 0 24 24">
                                                                    <path fill="currentColor" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                                                                    <path fill="currentColor" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                                                                    <path fill="currentColor" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
                                                                    <path fill="currentColor" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
                                                                </svg>
                                                                "Continue with Google"
                                                            </button>

                                                            <button
                                                                class="oauth-btn github-btn"
                                                                disabled=move || cloud_login_pending.get()
                                                                on:click=move |ev: web_sys::MouseEvent| {
                                                                    ev.stop_propagation();
                                                                    do_oauth_login("github".to_string());
                                                                }>
                                                                <svg class="oauth-icon" viewBox="0 0 24 24">
                                                                    <path fill="currentColor" d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/>
                                                                </svg>
                                                                "Continue with GitHub"
                                                            </button>

                                                            <div class="cloud-divider">
                                                                <span>"or"</span>
                                                            </div>

                                                            <button
                                                                class="oauth-btn email-btn"
                                                                on:click=move |ev: web_sys::MouseEvent| {
                                                                    ev.stop_propagation();
                                                                    set_show_email_login.set(true);
                                                                    set_cloud_login_error.set(None);
                                                                }>
                                                                <svg class="oauth-icon" viewBox="0 0 24 24">
                                                                    <path fill="currentColor" d="M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z"/>
                                                                </svg>
                                                                "Continue with Email"
                                                            </button>
                                                        }.into_any()
                                                    }}
                                                </div>
                                            }.into_any()
                                        }}
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="chat-title">
                    <Suspense fallback=move || view! { "..." }>
                        {move || {
                            hostname_resource.get().map(|result| {
                                result.unwrap_or_else(|_| "ollama".to_string())
                            })
                        }}
                    </Suspense>
                </div>

                <div class="header-right">
                    <div class="status-dropdown">
                        <button class="status-button"
                                on:click=move |ev: web_sys::MouseEvent| {
                                    ev.stop_propagation();
                                    set_status_dropdown_open.update(|v| *v = !*v);
                                }>
                            <span class="status-dot"
                                  class:status-green=move || ollama_running.get() && !(brave_search_enabled.get() && brave_api_token.get().trim().is_empty())
                                  class:status-red=move || !ollama_running.get()
                                  class:status-yellow=move || toggle_pending.get() || (brave_search_enabled.get() && brave_api_token.get().trim().is_empty())>
                            </span>
                            "Status"
                        </button>
                        <div class="status-menu"
                             class:hidden=move || !status_dropdown_open.get()
                             on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()>
                            <div class="status-menu-item">
                                <span class="status-label">"Ollama Serve"</span>
                                <label class="toggle-switch">
                                    <input type="checkbox"
                                           id="ollama-toggle"
                                           prop:checked=move || ollama_running.get()
                                           prop:disabled=move || toggle_pending.get()
                                           on:change=move |_| {
                                               set_toggle_pending.set(true);
                                               toggle_action.dispatch(());
                                           } />
                                    <span class="slider"></span>
                                </label>
                            </div>

                            // Brave Search toggle with hover submenu
                            <div class="status-menu-item brave-search-item"
                                 on:mouseenter=move |_| set_brave_submenu_open.set(true)
                                 on:mouseleave=move |_| set_brave_submenu_open.set(false)>
                                <span class="status-label">"Web Search"</span>
                                <label class="toggle-switch">
                                    <input type="checkbox"
                                           id="brave-toggle"
                                           prop:checked=move || brave_search_enabled.get()
                                           on:change=move |_| {
                                               let new_val = !brave_search_enabled.get();
                                               set_brave_search_enabled.set(new_val);
                                               #[cfg(target_arch = "wasm32")]
                                               {
                                                   if let Some(window) = web_sys::window() {
                                                       if let Ok(Some(storage)) = window.local_storage() {
                                                           let _ = storage.set_item("brave_search_enabled", if new_val { "true" } else { "false" });
                                                       }
                                                   }
                                               }
                                           } />
                                    <span class="slider"></span>
                                </label>

                                // Brave Search submenu (appears on hover)
                                <div class="brave-submenu"
                                     class:hidden=move || !brave_submenu_open.get()
                                     on:mouseenter=move |_| set_brave_submenu_open.set(true)
                                     on:mouseleave=move |_| set_brave_submenu_open.set(false)>
                                    <div class="brave-submenu-content">
                                        <div class="brave-submenu-header">"Brave Search API"</div>
                                        <div class="brave-token-row">
                                            <input
                                                type="password"
                                                class="brave-token-input"
                                                placeholder="Enter API Token"
                                                prop:value=move || brave_api_token.get()
                                                on:input=move |ev| {
                                                    let token = event_target_value(&ev);
                                                    set_brave_api_token.set(token.clone());
                                                    set_brave_test_status.set(None);
                                                }
                                                on:click=move |ev: web_sys::MouseEvent| ev.stop_propagation()
                                                on:keydown=move |ev: web_sys::KeyboardEvent| {
                                                    ev.stop_propagation();
                                                    if ev.key() == "Enter" {
                                                        let token = brave_api_token.get();
                                                        #[cfg(target_arch = "wasm32")]
                                                        {
                                                            if let Some(window) = web_sys::window() {
                                                                if let Ok(Some(storage)) = window.local_storage() {
                                                                    let _ = storage.set_item("brave_api_token", &token);
                                                                }
                                                            }
                                                        }
                                                        set_brave_test_status.set(Some("Saved!".to_string()));
                                                    }
                                                }
                                            />
                                        </div>
                                        <div class="brave-btn-row">
                                            <button
                                                class="brave-save-btn"
                                                on:click=move |ev: web_sys::MouseEvent| {
                                                    ev.stop_propagation();
                                                    let token = brave_api_token.get();
                                                    #[cfg(target_arch = "wasm32")]
                                                    {
                                                        if let Some(window) = web_sys::window() {
                                                            if let Ok(Some(storage)) = window.local_storage() {
                                                                let _ = storage.set_item("brave_api_token", &token);
                                                            }
                                                        }
                                                    }
                                                    set_brave_test_status.set(Some("Saved!".to_string()));
                                                }>
                                                "Save"
                                            </button>
                                            <button
                                                class="brave-test-btn"
                                                prop:disabled=move || brave_test_pending.get()
                                                on:click=move |ev: web_sys::MouseEvent| {
                                                    ev.stop_propagation();
                                                    let token = brave_api_token.get();
                                                    if token.trim().is_empty() {
                                                        set_brave_test_status.set(Some("Enter token first".to_string()));
                                                        return;
                                                    }
                                                    set_brave_test_pending.set(true);
                                                    set_brave_test_status.set(Some("Testing...".to_string()));
                                                    spawn_local(async move {
                                                        match test_brave_api(token).await {
                                                            Ok(response) => {
                                                                if response.success {
                                                                    set_brave_test_status.set(Some("API working!".to_string()));
                                                                } else {
                                                                    set_brave_test_status.set(Some(response.error.unwrap_or("Failed".to_string())));
                                                                }
                                                            }
                                                            Err(e) => {
                                                                set_brave_test_status.set(Some(format!("Error: {}", e)));
                                                            }
                                                        }
                                                        set_brave_test_pending.set(false);
                                                    });
                                                }>
                                                {move || if brave_test_pending.get() { "..." } else { "Test" }}
                                            </button>
                                        </div>
                                        // Status message
                                        {move || {
                                            brave_test_status.get().map(|status| {
                                                let is_success = status.contains("working") || status.contains("Saved");
                                                view! {
                                                    <div class="brave-status"
                                                         class:success=is_success
                                                         class:error=!is_success>
                                                        {status}
                                                    </div>
                                                }
                                            })
                                        }}
                                        <a href="https://brave.com/search/api/"
                                           target="_blank"
                                           rel="noopener noreferrer"
                                           class="brave-api-link">
                                            "Get API Key →"
                                        </a>
                                    </div>
                                </div>
                            </div>

                            <div class="status-divider"></div>

                            <div class="theme-section">
                                <div class="theme-label">"Theme"</div>
                                <div class="theme-options">
                                    <div class="theme-option"
                                         class:active=move || current_theme.get() == "light"
                                         on:click={
                                             let apply = apply_theme.clone();
                                             move |_| apply("light".to_string())
                                         }>
                                        <span class="theme-dot light"></span>
                                        "Light"
                                    </div>
                                    <div class="theme-option"
                                         class:active=move || current_theme.get() == "dark"
                                         on:click={
                                             let apply = apply_theme.clone();
                                             move |_| apply("dark".to_string())
                                         }>
                                        <span class="theme-dot dark"></span>
                                        "Dark"
                                    </div>
                                    <div class="theme-option"
                                         class:active=move || current_theme.get() == "amoled"
                                         on:click={
                                             let apply = apply_theme.clone();
                                             move |_| apply("amoled".to_string())
                                         }>
                                        <span class="theme-dot amoled"></span>
                                        "AMOLED"
                                    </div>
                                    <div class="theme-option"
                                         class:active=move || current_theme.get() == "hacker"
                                         on:click={
                                             let apply = apply_theme.clone();
                                             move |_| apply("hacker".to_string())
                                         }>
                                        <span class="theme-dot hacker"></span>
                                        "Hacker"
                                    </div>
                                    <div class="theme-option"
                                         class:active=move || current_theme.get() == "nordic"
                                         on:click={
                                             let apply = apply_theme.clone();
                                             move |_| apply("nordic".to_string())
                                         }>
                                        <span class="theme-dot nordic"></span>
                                        "Nordic"
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            // Backdrop for status dropdown
            <div class="menu-backdrop"
                 class:hidden=move || !status_dropdown_open.get()
                 on:click=move |_| set_status_dropdown_open.set(false)
                 on:touchend=move |_| set_status_dropdown_open.set(false)>
            </div>

            // Download progress bars
            <div class="download-progress-container">
                {move || {
                    let downloads: Vec<_> = active_downloads.get()
                        .into_iter()
                        .filter(|d| !d.done || d.error.is_some())
                        .collect();

                    downloads.into_iter().map(|dl| {
                        let model_name = dl.model.clone();
                        let model_for_hide = dl.model.clone();
                        let model_for_cancel = dl.model.clone();
                        let model_for_cancel_update = dl.model.clone();
                        let status = dl.status.clone();
                        let status_for_check = status.clone();
                        let percent = dl.percent;
                        let speed = dl.speed.clone();
                        let is_done = dl.done;

                        let is_complete = status_for_check == "Complete";
                        let is_cancelled = status_for_check == "Cancelled";
                        let can_cancel = !is_done && !is_complete && !is_cancelled;

                        view! {
                            <div class="download-progress-bar">
                                <div class="download-info">
                                    <span class="download-model">{model_name}</span>
                                    <span class="download-status"
                                          class:download-complete=is_complete>
                                        {status}
                                    </span>
                                    {if !speed.is_empty() {
                                        view! { <span class="download-speed">{speed}</span> }.into_any()
                                    } else {
                                        view! { <></> }.into_any()
                                    }}
                                    // Cancel button - stops the download
                                    {if can_cancel {
                                        view! {
                                            <button class="download-cancel"
                                                    title="Cancel download"
                                                    on:click=move |_| {
                                                        let model = model_for_cancel.clone();
                                                        let model_update = model_for_cancel_update.clone();
                                                        spawn_local(async move {
                                                            let _ = cancel_model_pull(model).await;
                                                        });
                                                        set_active_downloads.update(|downloads| {
                                                            if let Some(d) = downloads.iter_mut().find(|d| d.model == model_update) {
                                                                d.done = true;
                                                                d.status = "Cancelled".to_string();
                                                            }
                                                        });
                                                    }>
                                                "✕"
                                            </button>
                                        }.into_any()
                                    } else {
                                        view! { <></> }.into_any()
                                    }}
                                    // Hide button - just removes from UI
                                    <button class="download-hide"
                                            title="Hide"
                                            on:click=move |_| {
                                                set_active_downloads.update(|downloads| {
                                                    downloads.retain(|d| d.model != model_for_hide);
                                                });
                                            }>
                                        "−"
                                    </button>
                                </div>
                                <div class="progress-track">
                                    <div class="progress-fill"
                                         style:width=format!("{}%", percent)>
                                    </div>
                                </div>
                            </div>
                        }
                    }).collect_view()
                }}
            </div>

            // Chat window
            <div id="chat-window" class="chat-window">
                <For
                    each=move || messages.get()
                    key=|msg| format!("{}-{}", msg.role, msg.text.len())
                    children=move |msg| {
                        let is_user = msg.role == "user";
                        let is_empty_ai = msg.role == "ai" && msg.text.is_empty();
                        let msg_text = msg.text.clone();

                        view! {
                            <div class="chat-bubble"
                                 class:user-bubble=is_user
                                 class:ai-bubble=!is_user>
                                {if is_empty_ai {
                                    // Thinking animation
                                    view! {
                                        <span class="thinking">
                                            <span class="msg-prefix">
                                                <Suspense fallback=move || view! { "[...]" }>
                                                    {move || hostname_resource.get().map(|h| {
                                                        format!("[{}]", h.unwrap_or_else(|_| "ollama".to_string()))
                                                    })}
                                                </Suspense>
                                            </span>
                                            <span class="thinking-dots">
                                                <span class="thinking-dot"></span>
                                                <span class="thinking-dot"></span>
                                                <span class="thinking-dot"></span>
                                            </span>
                                        </span>
                                    }.into_any()
                                } else if is_user {
                                    // User message - plain text
                                    view! { <span>{msg_text}</span> }.into_any()
                                } else {
                                    // AI message with hostname prefix and markdown rendering
                                    let rendered_html = markdown_to_html(&msg_text);
                                    view! {
                                        <div class="ai-message-content">
                                            <span class="msg-prefix">
                                                <Suspense fallback=move || view! { "[...]:" }>
                                                    {move || hostname_resource.get().map(|h| {
                                                        format!("[{}]: ", h.unwrap_or_else(|_| "ollama".to_string()))
                                                    })}
                                                </Suspense>
                                            </span>
                                            <div class="markdown-content" inner_html=rendered_html></div>
                                        </div>
                                    }.into_any()
                                }}
                            </div>
                        }
                    }
                />
            </div>

            // Input area
            <div class="chat-input-area">
                <textarea
                    id="prompt-input"
                    placeholder="Type your message..."
                    rows="1"
                    autofocus=true
                    prop:value=move || input.get()
                    on:input=move |ev| set_input.set(event_target_value(&ev))
                    on:keydown=move |ev: web_sys::KeyboardEvent| {
                        if ev.key() == "Enter" && !ev.shift_key() && !ev.alt_key() {
                            ev.prevent_default();
                            do_send();
                        }
                    }
                    disabled=move || is_streaming.get()
                ></textarea>
                <button id="send-button"
                        type="button"
                        on:click=move |_: web_sys::MouseEvent| do_send()
                        disabled=move || is_streaming.get()>
                    "➤"
                </button>
            </div>
        </div>
    }
}
