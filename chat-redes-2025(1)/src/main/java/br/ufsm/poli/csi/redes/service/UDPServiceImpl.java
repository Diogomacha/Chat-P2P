package br.ufsm.poli.csi.redes.service;

import br.ufsm.poli.csi.redes.model.Mensagem;
import br.ufsm.poli.csi.redes.model.Usuario;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.*;
import java.util.HashSet;
import java.util.Set;

public class UDPServiceImpl implements UDPService {

    private static final int PORTA = 6789;
    private static final String GRUPO = "230.0.0.1";

    private MulticastSocket socket;
    private InetAddress grupo;
    private ObjectMapper objectMapper = new ObjectMapper();

    private Set<Usuario> usuarios = new HashSet<>();
    private Set<UDPServiceMensagemListener> listenersMensagem = new HashSet<>();
    private Set<UDPServiceUsuarioListener> listenersUsuario = new HashSet<>();

    public UDPServiceImpl() {
        try {
            grupo = InetAddress.getByName(GRUPO);
            socket = new MulticastSocket(PORTA);
            socket.joinGroup(grupo);

            // Thread que fica escutando mensagens recebidas
            new Thread(this::escutarMensagens).start();

            // Envia sonda inicial (para anunciar que entrou)
            Mensagem msg = new Mensagem();
            msg.setTipoMensagem(Mensagem.TipoMensagem.sonda);
            msg.setUsuario(InetAddress.getLocalHost().getHostName());
            enviarParaGrupo(msg);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void escutarMensagens() {
        byte[] buffer = new byte[1024];
        while (true) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String json = new String(packet.getData(), 0, packet.getLength());
                Mensagem mensagem = objectMapper.readValue(json, Mensagem.class);

                InetAddress remetenteAddr = packet.getAddress();
                Usuario remetente = new Usuario(mensagem.getUsuario(), Usuario.StatusUsuario.DISPONIVEL, remetenteAddr);

                switch (mensagem.getTipoMensagem()) {
                    case sonda:
                        notificarUsuarioAdicionado(remetente);
                        break;

                    case msg_individual:
                        notificarMensagem(mensagem.getMsg(), remetente, false);
                        break;

                    case msg_grupo:
                        notificarMensagem(mensagem.getMsg(), remetente, true);
                        break;

                    case fim_chat:
                        notificarFimChat(remetente);
                        notificarUsuarioRemovido(remetente);
                        break;

                    case disponivel:
                        notificarUsuarioAlterado(remetente);
                        break;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void enviarParaGrupo(Mensagem msg) throws IOException {
        String json = objectMapper.writeValueAsString(msg);
        byte[] buffer = json.getBytes();
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, grupo, PORTA);
        socket.send(packet);
    }

    private void notificarMensagem(String mensagem, Usuario remetente, boolean chatGeral) {
        for (UDPServiceMensagemListener listener : listenersMensagem) {
            listener.mensagemRecebida(mensagem, remetente, chatGeral);
        }
    }

    private void notificarFimChat(Usuario remetente) {
        for (UDPServiceMensagemListener listener : listenersMensagem) {
            listener.fimChatPelaOutraParte(remetente);
        }
    }

    private void notificarUsuarioAdicionado(Usuario usuario) {
        if (usuarios.add(usuario)) {
            for (UDPServiceUsuarioListener listener : listenersUsuario) {
                listener.usuarioAdicionado(usuario);
            }
        }
    }

    private void notificarUsuarioRemovido(Usuario usuario) {
        if (usuarios.remove(usuario)) {
            for (UDPServiceUsuarioListener listener : listenersUsuario) {
                listener.usuarioRemovido(usuario);
            }
        }
    }

    private void notificarUsuarioAlterado(Usuario usuario) {
        for (UDPServiceUsuarioListener listener : listenersUsuario) {
            listener.usuarioAlterado(usuario);
        }
    }

    // === Métodos da interface ===

    @Override
    public void enviarMensagem(String mensagem, Usuario destinatario, boolean chatGeral) {
        try {
            Mensagem msg = new Mensagem();
            msg.setMsg(mensagem);
            msg.setUsuario(InetAddress.getLocalHost().getHostName());
            msg.setTipoMensagem(chatGeral ? Mensagem.TipoMensagem.msg_grupo : Mensagem.TipoMensagem.msg_individual);
            enviarParaGrupo(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void usuarioAlterado(Usuario usuario) {
        try {
            Mensagem msg = new Mensagem();
            msg.setTipoMensagem(Mensagem.TipoMensagem.disponivel);
            msg.setUsuario(usuario.getNome());
            msg.setStatus(usuario.getStatus().toString());
            enviarParaGrupo(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void addListenerMensagem(UDPServiceMensagemListener listener) {
        listenersMensagem.add(listener);
    }

    @Override
    public void fimChat(Usuario usuario) {
        try {
            Mensagem msg = new Mensagem();
            msg.setTipoMensagem(Mensagem.TipoMensagem.fim_chat);
            msg.setUsuario(usuario.getNome());
            enviarParaGrupo(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void addListenerUsuario(UDPServiceUsuarioListener listener) {
        listenersUsuario.add(listener);
    }
}
