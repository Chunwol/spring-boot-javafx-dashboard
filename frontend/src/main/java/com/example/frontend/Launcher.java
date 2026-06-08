package com.example.frontend;

/**
 * Fat-jar 실행 진입점.
 *
 * <p>JavaFX {@code Application} 서브클래스({@link DashboardApp})를 JAR의 Main-Class로
 * 직접 지정하면, 모듈패스가 아닌 클래스패스 실행 시 JavaFX 런타임이
 * "main 메서드가 void를 반환해야 한다"는 오류로 기동에 실패한다.</p>
 *
 * <p>Application을 상속하지 않는 별도 Launcher를 Main-Class로 두고
 * 여기서 {@link DashboardApp#main}을 호출하면 정상 기동된다.</p>
 */
public class Launcher {
    public static void main(String[] args) {
        DashboardApp.main(args);
    }
}
