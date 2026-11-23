use crate::android::susfs::{
    config::model::{Config, OpenRedirectItem, SusKstatItem, SusKstatStatically, SusPathItem},
    enums::{SusKstatType, UidScheme},
    macros::ensure_path_exists,
};
use anyhow::Result;

impl Config {
    pub fn set_enabled(&mut self, enabled: bool) -> &mut Self {
        self.enabled = enabled;
        self
    }

    pub fn set_cmdline_or_bootconfig(&mut self, path: &str) -> Result<&mut Self> {
        if !path.is_empty() {
            ensure_path_exists!(path);
        }
        self.cmdline_or_bootconfig = path.to_string();
        Ok(self)
    }

    pub fn set_avc_log_spoofing(&mut self, enabled: bool) -> &mut Self {
        self.avc_log_spoofing = enabled;
        self
    }

    pub fn set_logging(&mut self, enabled: bool) -> &mut Self {
        self.logging = enabled;
        self
    }

    pub fn set_hide_sus_mnts_for_non_su_procs(&mut self, enabled: bool) -> &mut Self {
        self.hide_sus_mnts_for_non_su_procs = enabled;
        self
    }

    pub fn set_uname(&mut self, release: &str, version: &str) -> Result<&mut Self> {
        self.uname.version = version.to_string();
        self.uname.release = release.to_string();

        Ok(self)
    }

    pub fn add_sus_path(&mut self, path: &str, is_loop: bool) -> Result<&mut Self> {
        ensure_path_exists!(path);
        self.sus_path.replace(SusPathItem {
            path: path.to_string(),
            is_loop,
        });
        Ok(self)
    }

    pub fn remove_sus_path(&mut self, path: &str) -> &mut Self {
        self.sus_path.remove(path);
        self
    }

    pub fn has_sus_path(&self, path: &str) -> bool {
        self.sus_path.iter().any(|item| item.path == path)
    }

    /// 删除所有“管理器 apk 自动注册”产生的 sus_path 条目，**包含当前路径**。
    ///
    /// 为什么不保留当前路径：本构建内核 `ignore_ksu_allowlist = true`
    /// （见 ksu_umount_gate.c），对 uid >= 10000 的进程全量隐藏，管理器进程
    /// 打开自己的 base.apk 会在 `inode_permission` 被判 -ENOENT，进程在
    /// `Application.onCreate` 之前就死掉——表现为开机必闪退、重装即恢复。
    /// 因此按“路径含管理器包名且以 base.apk 结尾”识别并全部清掉，
    /// 不碰用户手动添加的其它 sus_path。返回被清理的条数。
    pub fn prune_manager_apks(&mut self) -> usize {
        let pkg = crate::defs::DEFAULT_PACKAGE_NAME;
        let before = self.sus_path.len();
        self.sus_path.retain(|item| {
            !(item.path.contains(pkg) && item.path.ends_with("base.apk"))
        });
        before - self.sus_path.len()
    }

    pub fn add_sus_kstat(&mut self, path: &str, full_clone: bool) -> Result<&mut Self> {
        ensure_path_exists!(path);
        self.sus_kstat.replace(SusKstatItem {
            path: path.to_string(),
            spoof_type: if full_clone {
                SusKstatType::FullClone
            } else {
                SusKstatType::Normal
            },
            statically: None,
        });
        Ok(self)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn add_sus_kstat_statically(
        &mut self,
        path: &str,
        ino: Option<i64>,
        dev: Option<i64>,
        nlink: Option<i64>,
        size: Option<i64>,
        atime: Option<i64>,
        atime_nsec: Option<i64>,
        mtime: Option<i64>,
        mtime_nsec: Option<i64>,
        ctime: Option<i64>,
        ctime_nsec: Option<i64>,
        blocks: Option<i64>,
        blksize: Option<i64>,
    ) -> Result<&mut Self> {
        ensure_path_exists!(path);
        self.sus_kstat.replace(SusKstatItem {
            path: path.to_string(),
            spoof_type: SusKstatType::Statically,
            statically: Some(SusKstatStatically {
                ino,
                dev,
                nlink,
                size,
                atime,
                atime_nsec,
                mtime,
                mtime_nsec,
                ctime,
                ctime_nsec,
                blocks,
                blksize,
            }),
        });
        Ok(self)
    }

    pub fn remove_sus_kstat(&mut self, path: &str) -> &mut Self {
        self.sus_kstat.remove(path);
        self
    }

    pub fn add_open_redirect(
        &mut self,
        target_path: &str,
        redirected_path: &str,
        uid_scheme: &UidScheme,
    ) -> Result<&mut Self> {
        ensure_path_exists!(target_path);
        ensure_path_exists!(redirected_path);
        self.open_redirect.replace(OpenRedirectItem {
            target_path: target_path.to_string(),
            redirected_path: redirected_path.to_string(),
            uid_scheme: *uid_scheme,
        });
        Ok(self)
    }

    pub fn remove_open_redirect(&mut self, target_path: &str) -> &mut Self {
        self.open_redirect.remove(target_path);
        self
    }

    pub fn add_sus_map(&mut self, path: &str) -> Result<&mut Self> {
        ensure_path_exists!(path);
        self.sus_map.insert(path.to_string());
        Ok(self)
    }

    pub fn remove_sus_map(&mut self, path: &str) -> &mut Self {
        self.sus_map.remove(path);
        self
    }
}
