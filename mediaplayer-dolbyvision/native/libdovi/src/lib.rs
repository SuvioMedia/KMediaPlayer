use dolby_vision::rpu::{ConversionMode, dovi_rpu::DoviRpu};
use std::{ptr, slice};

fn convert_profile7_to81(bytes: &[u8]) -> Option<Vec<u8>> {
    let mut rpu = DoviRpu::parse_unspec62_nalu(bytes).ok()?;
    if rpu.dovi_profile != 7 {
        return None;
    }
    rpu.convert_with_mode(ConversionMode::To81).ok()?;
    rpu.write_hevc_unspec62_nalu().ok()
}

/// Allocates an initialized byte buffer for foreign runtimes such as WebAssembly.
/// The returned buffer must be released with `cmp_dovi_free_buffer` using the same length.
#[unsafe(no_mangle)]
pub extern "C" fn cmp_dovi_allocate_buffer(len: usize) -> *mut u8 {
    if len == 0 {
        return ptr::null_mut();
    }
    let mut boxed = vec![0_u8; len].into_boxed_slice();
    let pointer = boxed.as_mut_ptr();
    std::mem::forget(boxed);
    pointer
}

/// Converts one HEVC UNSPEC-62 RPU NAL unit with libdovi's Profile 8.1 mode.
/// The returned buffer must be released with `cmp_dovi_free_buffer`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cmp_dovi_convert_profile7_to81(
    input: *const u8,
    input_len: usize,
    output_len: *mut usize,
) -> *mut u8 {
    if input.is_null() || output_len.is_null() {
        return ptr::null_mut();
    }
    let bytes = unsafe { slice::from_raw_parts(input, input_len) };
    let converted = convert_profile7_to81(bytes);
    let Some(bytes) = converted else {
        unsafe { *output_len = 0 };
        return ptr::null_mut();
    };
    let mut boxed = bytes.into_boxed_slice();
    let pointer = boxed.as_mut_ptr();
    unsafe { *output_len = boxed.len() };
    std::mem::forget(boxed);
    pointer
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn cmp_dovi_free_buffer(buffer: *mut u8, len: usize) {
    if !buffer.is_null() {
        let slice = ptr::slice_from_raw_parts_mut(buffer, len);
        drop(unsafe { Box::from_raw(slice) });
    }
}

#[cfg(target_os = "android")]
mod android {
    use super::convert_profile7_to81;
    use jni::{
        JNIEnv,
        objects::{JByteArray, JObject},
        sys::jbyteArray,
    };
    use std::ptr;

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_io_github_kdroidfilter_composemediaplayer_dolbyvision_LibDoviRpuConverter_nativeConvertProfile7To81(
        env: JNIEnv,
        _instance: JObject,
        input: JByteArray,
    ) -> jbyteArray {
        let Ok(bytes) = env.convert_byte_array(&input) else {
            return ptr::null_mut();
        };
        let Some(converted) = convert_profile7_to81(&bytes) else {
            return ptr::null_mut();
        };
        env.byte_array_from_slice(&converted)
            .map(JByteArray::into_raw)
            .unwrap_or_else(|_| ptr::null_mut())
    }
}
