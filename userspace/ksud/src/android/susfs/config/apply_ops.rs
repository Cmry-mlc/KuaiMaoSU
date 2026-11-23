use crate::android::susfs::{api::prelude as api, config::model::Config, enums};
use anyhow::{Result, anyhow, bail};

fn apply_multiply<I, T, F>(iterable: I, mut f: F) -> Result<()>
where
    I: IntoIterator<Item = T>,
    F: FnMut(T) -> Result<()>,
{
    let mut errors = String::new();
    let mut success = true;

    for item in iterable {
        if let Err(e) = f(item) {
            errors.push_str(&format!("{}\n", e));
            success = false;
        }
    }

    success
        .then_some(())
        .ok_or_else(|| anyhow!(errors.trim().to_string()))
}

impl Config {
    pub fn apply_cmdline_or_bootconfig(&self) -> Result<&Self> {
        api::set_cmdline_or_bootconfig(&self.cmdline_or_bootconfig)?;
        Ok(self)
    }

    pub fn apply_avc_log_spoofing(&self) -> Result<&Self> {
        api::enable_avc_log_spoofing(self.avc_log_spoofing)?;
        Ok(self)
    }

    pub fn apply_logging(&self) -> Result<&Self> {
        api::enable_log(self.logging)?;
        Ok(self)
    }

    pub fn apply_hide_sus_mnts_for_non_su_procs(&self) -> Result<&Self> {
        api::hide_sus_mnts_for_non_su_procs(self.hide_sus_mnts_for_non_su_procs)?;
        Ok(self)
    }

    pub fn apply_uname(&self) -> Result<&Self> {
        api::set_uname(&self.uname.release, &self.uname.version)?;
        Ok(self)
    }

    pub fn apply_sus_path(&self) -> Result<&Self> {
        let pkg = crate::defs::DEFAULT_PACKAGE_NAME;
        apply_multiply(&self.sus_path, |i| {
            // 硬保险：管理器自己的 base.apk 绝不能下发给 SUSFS。本构建内核
            // `ignore_ksu_allowlist = true`，对 uid >= 10000 全量隐藏，管理器
            // 打开自己的 apk 会被 inode_permission 判 -ENOENT，进程在
            // Application.onCreate 之前就死掉。无论配置里为何残留，这里都跳过。
            if i.path.contains(pkg) && i.path.ends_with("base.apk") {
                log::warn!("skip applying manager own apk to sus_path: {}", i.path);
                return Ok(());
            }
            api::add_sus_path(&i.path, i.is_loop)
        })
        .map(|_| self)
    }

    pub fn init_sus_kstat(&self) -> Result<&Self> {
        apply_multiply(&self.sus_kstat, |i| match i.spoof_type {
            enums::SusKstatType::Normal | enums::SusKstatType::FullClone => {
                api::add_sus_kstat(&i.path)
            }
            enums::SusKstatType::Statically => Ok(()),
        })
        .map(|_| self)
    }

    pub fn final_sus_kstat(&self) -> Result<&Self> {
        apply_multiply(&self.sus_kstat, |i| match i.spoof_type {
            enums::SusKstatType::Normal => api::update_sus_kstat(&i.path, false),
            enums::SusKstatType::FullClone => api::update_sus_kstat(&i.path, true),
            enums::SusKstatType::Statically => {
                let Some(statically) = &i.statically else {
                    bail!(
                        "sus kstat statically undefined for {} which type is statically.",
                        i.path
                    );
                };
                api::add_sus_kstat_statically(
                    &i.path,
                    statically.ino,
                    statically.dev,
                    statically.nlink,
                    statically.size,
                    statically.atime,
                    statically.atime_nsec,
                    statically.mtime,
                    statically.mtime_nsec,
                    statically.ctime,
                    statically.ctime_nsec,
                    statically.blocks,
                    statically.blksize,
                )
            }
        })
        .map(|_| self)
    }

    pub fn apply_open_redirect(&self) -> Result<&Self> {
        apply_multiply(&self.open_redirect, |i| {
            api::add_open_redirect(&i.target_path, &i.redirected_path, &i.uid_scheme)
        })
        .map(|_| self)
    }

    pub fn apply_sus_map(&self) -> Result<&Self> {
        apply_multiply(&self.sus_map, |i| api::add_sus_map(i)).map(|_| self)
    }
}
