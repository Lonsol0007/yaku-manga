package yaku.domain.release.service

import yaku.domain.release.interactor.GetApplicationRelease
import yaku.domain.release.model.Release

interface ReleaseService {

    suspend fun latest(arguments: GetApplicationRelease.Arguments): Release?
}
