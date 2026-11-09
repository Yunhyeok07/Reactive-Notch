package com.reactivenotch.media

import android.service.notification.NotificationListenerService

/**
 * 내용은 비어 있다. 사용자가 "알림 접근"을 허용한 리스너 컴포넌트가 있어야
 * MediaSessionManager.getActiveSessions() 를 호출할 수 있어서 존재한다. 알림 내용은 읽지 않는다.
 */
class MediaListenerService : NotificationListenerService()
