pub mod app;

use crate::app::*;
use leptos::prelude::*;
use wasm_bindgen::prelude::wasm_bindgen;

#[wasm_bindgen]
pub fn hydrate() {
    #[cfg(feature = "hydrate")]
    {
        console_error_panic_hook::set_once();
        _ = console_log::init_with_level(log::Level::Debug);
        leptos::mount::hydrate_body(App);
    }
}
