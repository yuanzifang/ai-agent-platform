#!/usr/bin/env python3
"""本地模型 E2E smoke：真实 Ollama 模型 + 计算器工具 + 可选 RAG。

用法：
  python3 scripts/local-model-smoke.py
  LOCAL_SMOKE_KB_ID=161 python3 scripts/local-model-smoke.py

前提：本地 Ollama 已有 qwen2.5:7b；后端运行在 127.0.0.1:8080；dev 用户可创建 Agent。
脚本会临时创建 chat alias、CHAT 模型、Agent/会话，结束后清理。
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request


def call(method, url, token=None, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', f'Bearer {token}')
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'{method} {url} -> HTTP {e.code}: {e.read().decode(errors="replace")[:500]}') from e
    if payload.get('code') not in (None, 0):
        raise RuntimeError(f'{method} {url} -> {payload.get("message")}')
    return payload.get('data')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default=os.getenv('PLATFORM_BASE_URL', 'http://127.0.0.1:8080'))
    parser.add_argument('--model', default=os.getenv('LOCAL_SMOKE_MODEL', 'qwen2.5:7b'))
    parser.add_argument('--chat-alias', default=os.getenv('LOCAL_SMOKE_CHAT_ALIAS', 'qwen2.5:7b-chat'))
    parser.add_argument('--kb-id', type=int, default=int(os.getenv('LOCAL_SMOKE_KB_ID', '0')))
    parser.add_argument('--skip-rag', action='store_true')
    args = parser.parse_args()
    base = args.base_url.rstrip('/') + '/api/v1'
    created_alias = False
    model_created = False
    agent_ids = []
    conversation_ids = []

    try:
        models = subprocess.check_output(['ollama', 'list'], text=True)
        names = {line.split()[0] for line in models.splitlines()[1:] if line.strip()}
        if args.model not in names:
            raise RuntimeError(f'Ollama 未安装 {args.model}')
        if args.chat_alias not in names:
            subprocess.check_call(['ollama', 'cp', args.model, args.chat_alias])
            created_alias = True

        login = call('POST', base + '/auth/login', body={
            'username': os.getenv('PLATFORM_USER', 'dev'),
            'password': os.getenv('PLATFORM_PASSWORD', 'Dev@123456')
        })
        token = login['accessToken']
        providers = call('GET', base + '/provider', token) or []
        provider = next((p for p in providers if (p.get('providerType') or '').upper() == 'OLLAMA'), None)
        if not provider:
            raise RuntimeError('未找到 OLLAMA Provider')
        visible = call('GET', base + '/provider/models', token) or []
        model = next((m for m in visible if m.get('providerId') == provider['id']
                      and m.get('modelName') == args.chat_alias), None)
        if not model:
            created = call('POST', base + f'/provider/{provider["id"]}/models', token, {
                'modelName': args.chat_alias,
                'displayName': f'{args.chat_alias} 本地 smoke',
                'purpose': 'CHAT', 'contextWindow': 32768,
                'inputPrice': 0, 'outputPrice': 0
            })
            model_created = True
            model = {'modelName': args.chat_alias, 'modelId': created['id']}

        def create_agent(name, system_prompt, tools=None, kb=None):
            body = {
                'name': f'LOCAL-SMOKE-{name}-{os.getpid()}-{id(object())}',
                'systemPrompt': system_prompt,
                'modelConfig': {'modelName': args.chat_alias, 'temperature': 0.1},
                'agentCapabilities': {'functionCall': True, 'streamOutput': False},
                'visibility': 'PRIVATE'
            }
            if tools:
                body['tools'] = tools
            if kb:
                body['knowledgeBases'] = [{
                    'knowledgeBaseId': kb, 'enabled': True,
                    'retrievalConfig': {'topK': 3, 'scoreThreshold': 0, 'mode': 'hybrid'}
                }]
            agent = call('POST', base + '/agent', token, body)
            agent_ids.append(agent['id'])
            call('POST', base + f'/agent/{agent["id"]}/publish', token, {'force': True})
            return agent['id']

        def chat(agent_id, message):
            conv = call('POST', base + '/conversations', token, {'agentId': agent_id, 'title': 'local smoke'})
            conversation_ids.append(conv['id'])
            return call('POST', base + f'/conversations/{conv["id"]}/messages', token, {
                'message': message, 'stream': False, 'variables': {},
                'attachmentIds': [], 'allowWriteTools': False
            })

        tool_agent = create_agent('TOOL', '你是工具测试助手。必须调用计算器，不要心算。',
                                  tools=[{'toolId': 1, 'enabled': True}])
        tool_result = chat(tool_agent, '请使用计算器工具计算 123 + 456，然后只回答结果。')
        answer = str(tool_result.get('answer') or '')
        if '579' not in answer:
            raise RuntimeError(f'工具结果断言失败：{answer[:300]}')
        print(f'TOOL PASS answer={answer[:120]}')

        if not args.skip_rag:
            kb_id = args.kb_id
            if not kb_id:
                kbs = (call('GET', base + '/knowledge-base?pageSize=50', token) or {}).get('list') or []
                kb_id = next((k['id'] for k in kbs if (k.get('documentCount') or 0) > 0), 0)
            if kb_id:
                rag_agent = create_agent('RAG', '你是知识库问答助手，只根据检索内容回答。', kb=kb_id)
                rag = chat(rag_agent, '请根据知识库文档概括一个主要主题。')
                citations = rag.get('citations') or []
                if not citations:
                    raise RuntimeError('RAG 未返回 citation')
                print(f'RAG PASS kb={kb_id} citations={len(citations)} answer={str(rag.get("answer") or "")[:120]}')
            else:
                print('RAG SKIP 没有含文档的知识库')

        print('LOCAL-MODEL-SMOKE PASS')
    finally:
        # 清理：Agent/会话/模型/alias 全部 best-effort
        try:
            login = call('POST', base + '/auth/login', body={
                'username': os.getenv('PLATFORM_USER', 'dev'),
                'password': os.getenv('PLATFORM_PASSWORD', 'Dev@123456')
            })
            token = login['accessToken']
            for cid in conversation_ids:
                try: call('DELETE', base + f'/conversations/{cid}', token)
                except Exception: pass
            for aid in agent_ids:
                try: call('DELETE', base + f'/agent/{aid}', token)
                except Exception: pass
            if model_created and 'model' in locals():
                # 模型删除接口未暴露；保留 CHAT 模型供后续 smoke 复用
                pass
        except Exception:
            pass
        if created_alias:
            try: subprocess.check_call(['ollama', 'rm', args.chat_alias], stdout=subprocess.DEVNULL)
            except Exception: pass


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print(f'LOCAL-MODEL-SMOKE FAIL: {e}', file=sys.stderr)
        raise SystemExit(1)
