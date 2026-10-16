package com.tvhelper.app.di

import android.content.Context
import com.tvhelper.app.data.PairingStore
import com.tvhelper.app.data.SettingsStore
import com.tvhelper.app.data.TvRepository

/**
 * 轻量手动依赖注入。
 *
 * 说明：方案原稿写的是 Hilt；这里改用单例容器，原因是本工程不引入注解处理器，
 * 编译链更短、出错面更小。依赖关系很简单（SettingsStore/PairingStore → TvRepository），
 * 手写容器完全够用，后续要换 Hilt 也不影响现有分层。
 */
class AppContainer(context: Context) {

    val settings: SettingsStore = SettingsStore(context)

    val pairingStore: PairingStore = PairingStore(context)

    val repository: TvRepository = TvRepository(context, settings, pairingStore)
}
