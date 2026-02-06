#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
海康威视 ISAPI 模拟服务器
用于本地测试，无需真实设备

启动: python3 mock_server.py
访问: http://localhost:8000
用户名: admin
密码: admin123
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import hashlib
import time
import random
import re
from datetime import datetime, timedelta

# 配置
HOST = '0.0.0.0'
PORT = 8000
USERNAME = 'admin'
PASSWORD = 'admin123'
REALM = 'DS-2CD2032'

# 生成随机 nonce
def generate_nonce():
    return hashlib.md5(str(time.time()).encode()).hexdigest()

NONCE = generate_nonce()

class ISAPIHandler(BaseHTTPRequestHandler):
    
    def log_message(self, format, *args):
        print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {args[0]}")
    
    def send_auth_required(self):
        """发送 401 认证要求"""
        global NONCE
        NONCE = generate_nonce()
        self.send_response(401)
        self.send_header('WWW-Authenticate', 
            f'Digest realm="{REALM}", nonce="{NONCE}", qop="auth"')
        self.send_header('Content-Type', 'text/html')
        self.end_headers()
        self.wfile.write(b'Unauthorized')
    
    def verify_digest_auth(self):
        """验证 Digest 认证 - 简化版，接受任何正确格式的认证"""
        auth_header = self.headers.get('Authorization')
        if not auth_header or not auth_header.startswith('Digest '):
            print(f"[认证] 无认证头，返回 401")
            return False
        
        # 解析认证参数
        params = {}
        for match in re.finditer(r'(\w+)="?([^",]+)"?', auth_header):
            params[match.group(1)] = match.group(2)
        
        # 简化验证：只检查用户名和必要参数是否存在
        if 'username' not in params:
            print(f"[认证] 缺少 username 参数")
            return False
        
        if 'response' not in params:
            print(f"[认证] 缺少 response 参数")
            return False
        
        if 'nonce' not in params:
            print(f"[认证] 缺少 nonce 参数")
            return False
        
        # 接受认证（模拟服务器简化处理，不严格验证密码）
        print(f"[认证] 用户 {params['username']} 认证成功")
        return True
    
    def do_GET(self):
        if not self.verify_digest_auth():
            self.send_auth_required()
            return
        
        if '/ISAPI/System/deviceInfo' in self.path:
            self.send_device_info()
        else:
            self.send_response(404)
            self.end_headers()
    
    def do_POST(self):
        if not self.verify_digest_auth():
            self.send_auth_required()
            return
        
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length).decode('utf-8')
        
        if '/ISAPI/ContentMgmt/search' in self.path:
            self.send_search_response(body)
        else:
            self.send_response(404)
            self.end_headers()
    
    def send_device_info(self):
        """返回设备信息"""
        xml = '''<?xml version="1.0" encoding="UTF-8"?>
<DeviceInfo>
    <deviceName>Mock NVR</deviceName>
    <deviceID>mock-device-001</deviceID>
    <model>DS-7608NI-K2</model>
    <serialNumber>MOCK2024010100001</serialNumber>
    <firmwareVersion>V4.62.000</firmwareVersion>
</DeviceInfo>'''
        self.send_response(200)
        self.send_header('Content-Type', 'application/xml')
        self.end_headers()
        self.wfile.write(xml.encode())
    
    def send_search_response(self, request_body):
        """返回录像搜索结果"""
        # 解析请求中的时间范围
        start_match = re.search(r'<startTime>([^<]+)</startTime>', request_body)
        end_match = re.search(r'<endTime>([^<]+)</endTime>', request_body)
        track_match = re.search(r'<trackID>([^<]+)</trackID>', request_body)
        
        track_id = track_match.group(1) if track_match else '101'
        
        # 生成模拟的录像记录
        items = []
        if start_match and end_match:
            try:
                start_str = start_match.group(1)[:19]
                end_str = end_match.group(1)[:19]
                start_time = datetime.strptime(start_str, '%Y-%m-%dT%H:%M:%S')
                end_time = datetime.strptime(end_str, '%Y-%m-%dT%H:%M:%S')
                
                # 每20分钟生成一个录像记录
                current = start_time
                index = 0
                while current < end_time and index < 10:
                    rec_end = min(current + timedelta(minutes=20), end_time)
                    
                    # 模拟的下载路径
                    playback_uri = f"rtsp://localhost/Streaming/tracks/{track_id}?starttime={current.strftime('%Y%m%dT%H%M%S')}Z"
                    download_path = f"/ISAPI/ContentMgmt/download?playbackURI={playback_uri}"
                    
                    items.append(f'''
        <searchMatchItem>
            <sourceID>mock-source-{index}</sourceID>
            <trackID>{track_id}</trackID>
            <startTime>{current.strftime('%Y-%m-%dT%H:%M:%S')}Z</startTime>
            <endTime>{rec_end.strftime('%Y-%m-%dT%H:%M:%S')}Z</endTime>
            <eventType>continuous</eventType>
            <mediaSegmentDescriptor>
                <contentType>video</contentType>
                <playbackURI>{playback_uri}</playbackURI>
            </mediaSegmentDescriptor>
            <downloadPath>{download_path}</downloadPath>
        </searchMatchItem>''')
                    
                    current = rec_end
                    index += 1
            except Exception as e:
                print(f"解析时间失败: {e}")
        
        xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<CMSearchResult>
    <searchID>mock-search-{int(time.time())}</searchID>
    <responseStatus>true</responseStatus>
    <responseStatusStrg>OK</responseStatusStrg>
    <numOfMatches>{len(items)}</numOfMatches>
    <matchList>{"".join(items)}
    </matchList>
</CMSearchResult>'''
        
        self.send_response(200)
        self.send_header('Content-Type', 'application/xml')
        self.end_headers()
        self.wfile.write(xml.encode())
        print(f"[搜索] 返回 {len(items)} 条模拟录像记录")

def main():
    server = HTTPServer((HOST, PORT), ISAPIHandler)
    print("=" * 50)
    print("海康威视 ISAPI 模拟服务器")
    print("=" * 50)
    print(f"地址: http://localhost:{PORT}")
    print(f"用户名: 任意 (模拟模式)")
    print(f"密码: 任意 (模拟模式)")
    print("=" * 50)
    print("\n支持的接口:")
    print("  GET  /ISAPI/System/deviceInfo - 设备信息")
    print("  POST /ISAPI/ContentMgmt/search - 录像搜索")
    print("\n提示: 在网页中使用以下配置测试:")
    print(f"  设备IP: localhost")
    print(f"  端口: {PORT}")
    print(f"  用户名/密码: 任意值")
    print("\n按 Ctrl+C 停止服务器\n")
    
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务器已停止")
        server.shutdown()

if __name__ == '__main__':
    main()
