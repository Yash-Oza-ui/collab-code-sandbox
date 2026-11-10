import CreateRoomScreen from './CreateRoomScreen'
import RoomPage from './RoomPage'

function App() {
  // Exactly "/" shows the create-room screen. Every other path goes to RoomPage,
  // which handles room links and shows its own message for invalid ones.
  return window.location.pathname === '/' ? <CreateRoomScreen /> : <RoomPage />
}

export default App