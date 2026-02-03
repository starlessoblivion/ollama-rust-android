#[cfg(feature = "ssr")]
#[tokio::main]
async fn main() {
    use ollama_rust::app::*;
    use axum::routing::post;
    use axum::Router;
    use leptos::prelude::*;
    use leptos_axum::{generate_route_list, LeptosRoutes};
    use tower_http::services::ServeDir;

    let conf = get_configuration(None).unwrap();
    let addr = conf.leptos_options.site_addr;
    let leptos_options = conf.leptos_options;
    let routes = generate_route_list(App);

    let app = Router::new()
        .route("/api/stream", post(stream_handler))
        .nest_service("/pkg", ServeDir::new(format!("{}/pkg", &leptos_options.site_root)).append_index_html_on_directories(false))
        .leptos_routes(&leptos_options, routes, {
            let leptos_options = leptos_options.clone();
            move || shell(leptos_options.clone())
        })
        .with_state(leptos_options);

    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    leptos::logging::log!("listening on http://{}", &addr);
    axum::serve(listener, app).await.unwrap();
}

#[cfg(feature = "ssr")]
#[derive(serde::Deserialize)]
pub struct PromptRequest {
    pub model: String,
    pub prompt: String,
}

#[cfg(feature = "ssr")]
async fn stream_handler(
    axum::extract::State(_state): axum::extract::State<leptos::prelude::LeptosOptions>,
    axum::Json(payload): axum::Json<PromptRequest>,
) -> axum::response::sse::Sse<std::pin::Pin<Box<dyn futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>> + Send>>> {
    use futures::StreamExt;
    use tokio_util::codec::{FramedRead, LinesCodec};
    use tokio_util::io::StreamReader;

    // Check if this is a cloud model request
    if payload.model.starts_with("cloud:") {
        let cloud_model = payload.model.strip_prefix("cloud:").unwrap_or(&payload.model);

        // For demo purposes, simulate a cloud model response
        // In production, this would call the actual Ollama Cloud API
        let response_text = format!(
            "[Cloud Demo] You asked: \"{}\"\n\n\
            This is a simulated response from cloud model '{}'. \
            In a production environment, this would connect to the actual Ollama Cloud API \
            to process your request using cloud-hosted models.\n\n\
            To use real cloud models, you'll need to:\n\
            1. Sign up for Ollama Cloud at ollama.com\n\
            2. Get your API credentials\n\
            3. Configure the cloud endpoint in your settings",
            payload.prompt.chars().take(100).collect::<String>(),
            cloud_model
        );

        let stream = async_stream::stream! {
            // Stream the response word by word for a more realistic effect
            for word in response_text.split_whitespace() {
                yield Ok(axum::response::sse::Event::default().data(format!("{} ", word)));
                tokio::time::sleep(tokio::time::Duration::from_millis(30)).await;
            }
            yield Ok(axum::response::sse::Event::default().data("__END__"));
        };
        return axum::response::sse::Sse::new(Box::pin(stream));
    }

    // Local Ollama model request
    let client = reqwest::Client::new();
    let res = client
        .post("http://localhost:11434/api/generate")
        .json(&serde_json::json!({
            "model": payload.model,
            "prompt": payload.prompt,
            "stream": true
        }))
        .send()
        .await;

    match res {
        Ok(response) => {
            let body_with_io_error = response.bytes_stream().map(|res| {
                res.map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))
            });
            let reader = StreamReader::new(body_with_io_error);
            let mut lines = FramedRead::new(reader, LinesCodec::new());

            let stream = async_stream::stream! {
                while let Some(Ok(line)) = lines.next().await {
                    if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
                        if let Some(text) = json["response"].as_str() {
                            yield Ok(axum::response::sse::Event::default().data(text));
                        }
                        if json["done"].as_bool().unwrap_or(false) {
                            yield Ok(axum::response::sse::Event::default().data("__END__"));
                        }
                    }
                }
            };
            axum::response::sse::Sse::new(Box::pin(stream))
        }
        Err(_) => {
            let error_stream = futures::stream::once(async {
                Ok(axum::response::sse::Event::default().data("[Error: Ollama not reachable]"))
            });
            axum::response::sse::Sse::new(Box::pin(error_stream))
        }
    }
}

#[cfg(not(feature = "ssr"))]
pub fn main() {}
