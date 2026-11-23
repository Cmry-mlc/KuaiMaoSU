use std::fs;
use std::path::{Path, PathBuf};

use anyhow::Result;
use log::{info, warn};
use serde::{Deserialize, Serialize};

use crate::{android::ksucalls, apk_sign, defs};

#[derive(Debug, Deserialize, Serialize)]
struct Config {
    size: u32,
    hash: String,
}

impl Config {
    fn hash_bytes(&self) -> Result<[u8; 64], String> {
        parse_hash(&self.hash)
    }

    pub fn set_hash_from_bytes(&mut self, hash: [u8; 64]) {
        self.hash = String::from_utf8_lossy(&hash).to_string();
    }
}

/// 在 /data/app 下查找默认管理器包名的 base.apk，用于配置丢失/被清空时自愈。
/// 兼容两种布局：/data/app/<pkg>-<rand>/base.apk 与
/// /data/app/~~<rand>/<pkg>-<rand>/base.apk。
pub(crate) fn find_default_manager_apk() -> Option<PathBuf> {
    let data_app = Path::new("/data/app");
    let pkg = defs::DEFAULT_PACKAGE_NAME;
    let entries = fs::read_dir(data_app).ok()?;
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name();
        let name = name.to_string_lossy();
        let dir = if name.starts_with(pkg) {
            Some(path)
        } else if name.starts_with("~~") {
            fs::read_dir(&path).ok().and_then(|sub| {
                sub.flatten().find(|e| {
                    e.file_name().to_string_lossy().starts_with(pkg)
                }).map(|e| e.path())
            })
        } else {
            None
        };
        if let Some(dir) = dir {
            let apk = dir.join("base.apk");
            if apk.is_file() {
                return Some(apk);
            }
        }
    }
    None
}

/// 配置缺失或被写空时，扫描已安装的管理器 APK，重新算签名并注册。
/// 这样即使旧版本 clear 把 .dynamic_manager 抹成空配置，开机也能自愈。
fn recover_from_apk() -> Result<()> {
    let apk = match find_default_manager_apk() {
        Some(p) => p,
        None => {
            warn!("dynamic manager config empty and no manager apk found, skip recovery");
            return Ok(());
        }
    };
    let apk = apk.to_string_lossy();
    let (size, hash_str) = apk_sign::get_apk_signature(&apk)?;
    let hash = parse_hash(&hash_str).map_err(anyhow::Error::msg)?;
    // 用同步 set：既写回配置，也立即让内核注册签名。
    set(size, hash)?;
    info!("dynamic manager recovered from apk: {}", apk);
    Ok(())
}

pub fn booted_load() -> Result<()> {
    let buf = match fs::read_to_string(defs::DYNAMIC_MANAGER) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            return recover_from_apk();
        }
        Err(e) => return Err(e.into()),
    };

    let json: Config = serde_json::from_str(&buf)?;
    if json.hash.is_empty() || json.size == 0 {
        // 旧版本 clear 会留下 {size:0,hash:""}，这里补一次自愈。
        return recover_from_apk();
    }
    let Ok(hash) = json.hash_bytes() else {
        return recover_from_apk();
    };

    ksucalls::dynamic_manager_set(json.size, hash)?;
    Ok(())
}

pub fn parse_hash(s: &str) -> Result<[u8; 64], String> {
    s.as_bytes()
        .try_into()
        .map_err(|_| "Incorrect hash".to_string())
}

pub fn clear() -> Result<()> {
    // 只从内核注销动态管理器签名，**保留** .dynamic_manager 配置。
    //
    // 旧实现会先把配置写成 {size:0,hash:""}，导致开机时 booted_load() 读到空
    // 配置直接 return，永远不再注册管理器 —— 一旦在动态管理器里删掉自己，
    // 重装 APK 也恢复不了 root（配置在 /data/adb，不随 APK 安装重建）。
    // 保留配置后，下次开机 booted_load() 会用原签名自动重新注册，实现自愈。
    ksucalls::dynamic_manager_clear()?;

    Ok(())
}

pub fn set(size: u32, hash: [u8; 64]) -> Result<()> {
    let mut json_raw = Config {
        size,
        hash: String::new(),
    };
    json_raw.set_hash_from_bytes(hash);

    let string = serde_json::to_string_pretty(&json_raw)?;

    fs::write(defs::DYNAMIC_MANAGER, string)?;

    ksucalls::dynamic_manager_set_synchronous(size, hash)?;
    Ok(())
}
