package io.github.junyoungwon.adsdk.internal

import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.ImageView

/**
 * 현재 프로젝트에서 노출 관찰을 허용한 화면 상태라면, 광고 이미지의 보이는 면적 비율을 계산한다.
 * BannerAdView 내부에서 FIT_CENTER로 배치한 ImageView를 대상으로 메인 스레드에서 호출한다.
 *
 * 먼저 [isImpressionObservationAllowed]로 부착과 가시성, 창 포커스와 지원하는 변환을 확인한다.
 * 허용되지 않으면 0을 반환한다. 따라서 0은 실제 가시 면적이 없거나 판정에서 제외된다는 뜻이며,
 * 반드시 이미지 전체가 화면 밖에 있다는 의미는 아니다. 예를 들어 포커스 없는 창도 0이다.
 *
 * 면적은 Drawable의 사각형을 imageMatrix로 변환해 구한다. FIT_CENTER의 빈 여백은 제외한다.
 * 이미지 사각형과 getGlobalVisibleRect의 클리핑 영역을 같은 root View 좌표로 맞춘 뒤,
 * 둘이 겹치는 면적을 전체 이미지 면적으로 나눈다.
 *
 * 다른 View나 창의 임의 덮임, 이미지의 투명 픽셀, 광고 라벨이 덮는 면적은 계산하지 않는다.
 * 회전과 배율 변환은 판정에서 제외하며 alpha는 0 여부만 확인한다.
 * 50% 기준 적용과 연속 1초, 중복 알림 여부는 호출하는 View와 Tracker가 판단한다.
 *
 * @return 0~1 범위의 면적 비율. 이미지나 유효한 면적이 없거나 관찰을 허용하지 않으면 0.
 */
internal fun ImageView.visibleImageFraction(): Float {
    val adImage = drawable ?: return 0f
    if (!isImpressionObservationAllowed()) return 0f

    // root View에서 잘리지 않은 영역과 ImageView 원점의 위치를 함께 얻는다.
    val visible = Rect()
    val offset = Point()
    if (!getGlobalVisibleRect(visible, offset)) return 0f

    // 이미지의 실제 배치 영역을 위의 클리핑 영역과 같은 좌표로 변환한다.
    val full = RectF(adImage.bounds)
    imageMatrix.mapRect(full)
    full.offset((offset.x + paddingLeft).toFloat(), (offset.y + paddingTop).toFloat())
    val fullArea = full.width() * full.height()
    if (fullArea <= 0f || !fullArea.isFinite()) return 0f
    val intersection = RectF(full)
    if (!intersection.intersect(RectF(visible))) return 0f
    return (intersection.width() * intersection.height() / fullArea).coerceIn(0f, 1f)
}

/**
 * 현재 프로젝트의 창과 View 상태 정책을 적용한다. 가시 면적과 경과 시간은 여기서 계산하지 않는다.
 * View와 상위 View가 보이고 완전히 투명하지 않아야 하며, 창에도 포커스가 있어야 한다.
 * 지원 범위를 벗어난 회전과 배율 변환이 있으면 관찰에서 제외한다.
 */
private fun ImageView.isImpressionObservationAllowed(): Boolean {
    if (!isAttachedToWindow || !isShown || windowVisibility != View.VISIBLE || !hasWindowFocus()) return false
    var ancestor: View? = this
    while (ancestor != null) {
        if (ancestor.visibility != View.VISIBLE || ancestor.alpha <= 0f ||
            ancestor.rotation != 0f || ancestor.rotationX != 0f || ancestor.rotationY != 0f ||
            ancestor.scaleX != 1f || ancestor.scaleY != 1f
        ) return false
        ancestor = ancestor.parent as? View
    }
    return true
}
