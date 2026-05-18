-- V18: 위젯 아이템에 대시보드 범위 추가, 패널에 이름 컬럼 추가

-- dashboard_widget_items: TEXT/IMAGE 위젯을 대시보드별로 스코핑
ALTER TABLE dashboard_widget_items ADD COLUMN dashboard_id INTEGER;

-- dashboard_panels: 사용자 지정 패널 이름
ALTER TABLE dashboard_panels ADD COLUMN panel_name TEXT;
