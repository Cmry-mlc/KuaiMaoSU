use anyhow::{Result, bail};
use std::{
    fs,
    io::{self, Read, Seek},
    os::fd::AsRawFd,
};

use crate::android::{susfs::config::model::Config, utils::daemonize};

// 注意：这里曾经有 `auto_register_manager_apk()`，在 boot_completed 时把管理器
// 自己的 base.apk 自动写进 sus_path。该做法在本项目构建下是致命的：
// 内核 `ignore_ksu_allowlist = true`，对 uid >= 10000 的进程全量隐藏，
// 管理器进程打开自己的 base.apk 会被 `inode_permission` 判 -ENOENT，
// 进程在 Application.onCreate 之前就死掉——表现为开机必闪退、重装即恢复。
// 已移除自动注册，并在 boot_completed / late-load 清掉历史版本留下的存量条目。

/// 清理持久化配置中所有管理器 base.apk 的 sus_path 条目并落盘。
///
/// 不依赖开机事件，供 LKM 的 boot-completed 与免解的 late-load 两路共用：
/// 免解模式不会走 `susfs::on_boot_completed()`，必须单独调用一次才能自愈。
pub fn prune_manager_apk_entries() {
    let mut config = Config::read_or_default();
    let pruned = config.prune_manager_apks();
    if pruned > 0 {
        match config.save() {
            Ok(_) => log::info!("pruned {pruned} manager apk sus_path entrie(s)"),
            Err(e) => log::warn!("failed to persist pruned sus_path: {e}"),
        }
    }
}


fn is_fuse_mounted() -> bool {
    fs::metadata("/sdcard/Android").is_ok()
}

fn wait_for_fuse_mounted() -> Result<()> {
    if is_fuse_mounted() {
        return Ok(());
    }

    let mut file = fs::File::open("/proc/mounts")?;
    let fd = file.as_raw_fd();

    let mut pfd = libc::pollfd {
        fd,
        events: libc::POLLPRI | libc::POLLERR,
        revents: 0,
    };

    log::info!("Polling mount event");

    loop {
        let mut dummy = String::new();
        let _ = file.seek(io::SeekFrom::Start(0));
        let _ = file.read_to_string(&mut dummy);

        if is_fuse_mounted() {
            break;
        }

        let ret = unsafe { libc::poll(&mut pfd, 1, -1) };
        if ret < 0 {
            bail!("Poll failed");
        }
    }

    Ok(())
}

fn handle_result<T>(ret: Result<T>, msg: &str) {
    match ret {
        Ok(_) => log::info!("successfully {msg}!"),
        Err(e) => log::warn!("{msg} failed: {e}!"),
    }
}

pub fn on_boot_completed() {
    log::info!("Start daemonize...");
    if let Err(e) = daemonize(false) {
        log::error!("unable to daemonize: {e}");
        return;
    }
    log::info!("Daemonize success.");

    log::info!("Waiting /sdcard to be mounted.");
    if let Err(e) = wait_for_fuse_mounted() {
        log::error!("Wait FUSE mount failed: {e}");
    }
    log::info!("Processing SUSFS.");

    // 无论 SUSFS 是否启用都先清理：历史版本自动注册的管理器 base.apk 会让进程
    // 打不开自己的 apk 而在启动阶段崩溃，先自愈存量脏数据再决定是否下发。
    prune_manager_apk_entries();

    let config = Config::read_or_default();

    if !config.is_enabled() {
        log::info!("SUSFS persisted configuration is disabled; skipping boot-completed apply");
        return;
    }

    handle_result(config.apply_cmdline_or_bootconfig(), "sus_cmdline");
    handle_result(config.final_sus_kstat(), "finalize sus_kstat");
    handle_result(config.apply_sus_map(), "sus_map");
    handle_result(config.apply_open_redirect(), "open_redirect");
    handle_result(config.apply_sus_path(), "sus_path and sus_path_loop");

    log::info!("SUSFS finished");
}

pub fn on_post_fs_data() {
    let config = Config::read_or_default();

    log::info!("on_post_fs_data triggered!");

    if !config.is_enabled() {
        log::info!("SUSFS persisted configuration is disabled; skipping post-fs-data apply");
        return;
    }

    handle_result(config.apply_avc_log_spoofing(), "avc_config");
    handle_result(config.init_sus_kstat(), "initialize sus_kstat");
    handle_result(config.apply_logging(), "sus_log");
    handle_result(
        config.apply_hide_sus_mnts_for_non_su_procs(),
        "hide sus mnts for non su procs",
    );
    handle_result(config.apply_uname(), "sus_uname");

    log::info!("on_post_fs_data finished!");
}
