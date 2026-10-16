# AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## Project Overview

PaiAgent-one is an enterprise-grade AI workflow orchestration platform with visual flow editor. It uses a custom DAG (Directed Acyclic Graph) engine to execute workflows composed of LLM nodes (OpenAI, DeepSeek, Qwen) and tool nodes (TTS, etc.).

## Build & Development Commands

### Backend (Spring Boot)
```bash
cd backend
./mvnw spring-boot:run              # Start backend server (default port 8084)
./mvnw clean package                # Build JAR package
./mvnw test                         # Run all tests
./mvnw test -Dtest=ClassName        # Run single test class
./mvnw test -Dtest=ClassName#methodName  # Run single test method
```

### Frontend (React + Vite)
```bash
cd frontend
npm install                         # Install dependencies
npm run dev                         # Start dev server (port 5173)
npm run build                       # Build for production (TypeScript check + Vite build)
npm run lint                        # Run ESLint
npm run preview                     # Preview production build
```

### Database Setup
```bash
mysql -u root -p < backend/src/main/resources/schema.sql
```

### Environment Configuration

**Backend:** Copy `backend/.env.example` to `backend/.env` and configure:
- `MYSQL_PASSWORD` - Required for database connection
- `JWT_SECRET` - Required for token signing (min 32 chars for production)
- `APP_AUTH_DEFAULT_USERNAME/PASSWORD` - Default admin account (**no built-in default**; both must be set together, and weak passwords such as `admin123` make startup fail)
- `SERVER_PORT` - Backend port (default: 8084)

**Frontend:** Copy `frontend/.env.example` to `frontend/.env.local` and configure:
- `VITE_API_BASE_URL` - Backend API URL (default: /api, proxied to localhost:8084)
- `VITE_API_PROXY_TARGET` - Override proxy target for local dev

## Architecture

### Backend Structure (`backend/src/main/java/com/paiagent/`)

**Core DAG Engine (`engine/`):**
- `WorkflowEngine.java`: Main orchestration engine that executes workflows end-to-end
- `EngineSelector.java`: Routes to DAG or LangGraph engine based on `engineType` field
- `dag/DAGParser.java`: Parses workflow config into DAG, performs topological sorting using Kahn's algorithm, and detects cycles using DFS
- `langgraph/`: LangGraph4j-based state graph engine for complex workflows with conditional branching
- `skill/`: Skills system with YAML Frontmatter + Markdown declarations, three-level progressive loading
- `executor/NodeExecutor.java`: Interface for all node executors
- `executor/NodeExecutorFactory.java`: Factory pattern to get executors by node type
- `executor/impl/`: Concrete implementations (InputNodeExecutor, OutputNodeExecutor, OpenAINodeExecutor, TTSNodeExecutor, etc.)
- `llm/`: Spring AI integration with ChatClientFactory for dynamic client creation
- `model/`: Data models (WorkflowConfig, WorkflowNode, WorkflowEdge)

**Application Layers:**
- `controller/`: REST API endpoints
- `service/`: Business logic layer
- `mapper/`: MyBatis-Plus data access layer
- `entity/`: Database entities (Workflow, ExecutionRecord, NodeDefinition, User)
- `dto/`: Data transfer objects
- `config/`: Configuration classes (WebMvcConfig, MyBatisConfig)
- `interceptor/`: AuthInterceptor for JWT token-based authentication
- `common/`: Common utilities and result wrappers

**Resources:**
- `resources/skills/`: Skill definitions (SKILL.md files with YAML Frontmatter)
- `resources/schema.sql`: Database initialization script

### Frontend Structure (`frontend/src/`)

**Core Components:**
- `components/FlowCanvas.tsx`: ReactFlow-based visual workflow editor
- `components/NodePanel.tsx`: Draggable node palette (LLM/Tool categories)
- `components/DebugDrawer.tsx`: Execution debugging panel with real-time logs and results
- `components/AudioPlayer.tsx`: Audio playback component for TTS output
- `components/SkillSelector.tsx`: Skill selection component for applying skills in workflows

**Pages:**
- `pages/LoginPage.tsx`: Authentication page
- `pages/MainPage.tsx`: Workflow list management
- `pages/EditorPage.tsx`: Main workflow editor with canvas, node panel, and debug drawer

**State Management (Zustand):**
- `store/authStore.ts`: User authentication state (token, user info)
- `store/workflowStore.ts`: Workflow editing state (nodes, edges, selected workflow)

**API Layer:**
- `api/`: Axios-based API client for backend communication
- `utils/request.ts`: Axios instance with auth interceptors

### Database Schema

Tables: `workflow`, `node_definition`, `execution_record`, `user`

Key features:
- JSON columns for workflow config (`flow_data`), execution results (`node_results`)
- Logical deletion using `deleted` field (MyBatis-Plus config)
- Pre-seeded node definitions for OpenAI, DeepSeek, Qwen, ZhiPu, AIPing, and TTS

## Workflow Execution Flow

1. User designs workflow in ReactFlow canvas (frontend)
2. Frontend serializes nodes/edges to JSON and saves via API
3. Backend stores workflow config in `workflow.flow_data`
4. On execution:
   - `EngineSelector` routes to appropriate engine (DAG or LangGraph)
   - For DAG: `DAGParser` validates (cycle detection) and sorts nodes topologically
   - For LangGraph: `GraphBuilder` constructs StateGraph with conditional routing
   - Engine executes nodes sequentially, passing output of node N as input to node N+1
   - Each node result is recorded in `ExecutionRecord.node_results`
5. Frontend displays execution results in `DebugDrawer` with logs and output data

## Key Technologies

- **Backend**: Spring Boot 3.4.1, Java 21, MyBatis-Plus 3.5.5, MySQL 8.0+, FastJSON2, Spring AI 1.0.0-M5, LangGraph4j 1.8.0-beta3, JJWT 0.12.7
- **Frontend**: React 18, TypeScript 5.6, Vite 6, ReactFlow (@xyflow/react), Ant Design 6, Tailwind CSS 4, Zustand 5
- **Authentication**: JWT-based auth (default admin account is disabled unless `APP_AUTH_DEFAULT_USERNAME/PASSWORD` are set; weak passwords are rejected at startup)
- **API Docs**: Swagger UI at http://localhost:8084/swagger-ui.html

## Development Notes

- Backend API requires JWT token in `Authorization` header (Bearer scheme)
- Frontend stores token in Zustand store and localStorage
- ReactFlow node types must match backend `NodeExecutor` implementations
- Node executors follow a common interface: `execute(WorkflowNode node, Map<String, Object> input) -> Map<String, Object>`
- DAG engine uses Kahn's algorithm for topological sort and DFS for cycle detection
- LangGraph engine uses StateGraph with AsyncNodeAction for async execution
- Skills are loaded from `resources/skills/` with three-level progressive loading (summary -> detail -> reference)
- LLM nodes use Spring AI ChatClient created dynamically via ChatClientFactory
- SSE (Server-Sent Events) used for real-time execution progress streaming
