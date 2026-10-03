//! Erzeugt die Kotlin-Bindings aus der gebauten Bibliothek (gleiche UniFFI-Version wie die Bibliothek).
fn main() {
    uniffi::uniffi_bindgen_main()
}
