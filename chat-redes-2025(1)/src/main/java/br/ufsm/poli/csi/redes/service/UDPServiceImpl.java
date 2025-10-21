package br.ufsm.poli.csi.redes.service;

import br.ufsm.poli.csi.redes.model.Mensagem;
import br.ufsm.poli.csi.redes.model.Usuario;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UDPServiceImpl implements UDPService {

    private final Map<InetAddress, Long> ultimasSondas = new ConcurrentHashMap<>();
    private final Map<InetAddress, Usuario> usuariosOnline = new ConcurrentHashMap<>();

    private Usuario usuario = null;

    private UDPServiceMensagemListener mensagemListener = null;
    private UDPServiceUsuarioListener usuarioListener = null;

    public UDPServiceImpl() {
        new Thread(new RecebeSonda()).start();
        new Thread(new EnviaSonda()).start();
        new Thread(new LimpaUsuariosInativos()).start();
    }


    private class EnviaSonda implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    Thread.sleep(5000);
                    if (usuario == null) continue;

                    Mensagem mensagem = new Mensagem();
                    mensagem.setTipoMensagem(Mensagem.TipoMensagem.sonda);
                    mensagem.setUsuario(usuario.getNome());
                    mensagem.setStatus(usuario.getStatus().toString());

                    ObjectMapper mapper = new ObjectMapper();
                    String strMensagem = mapper.writeValueAsString(mensagem);
                    byte[] bMensagem = strMensagem.getBytes();

                    for (int i = 1; i < 255; i++) {
                        DatagramPacket packet = new DatagramPacket(
                                bMensagem,
                                bMensagem.length,
                                InetAddress.getByName("192.168.0." + i),
                                8080
                        );
                        DatagramSocket socket = new DatagramSocket();
                        socket.send(packet);
                        socket.close();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private class RecebeSonda implements Runnable {
        @Override
        public void run() {
            try (DatagramSocket socket = new DatagramSocket(8080)) {
                while (true) {
                    byte[] buf = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    processarPacote(packet);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private class LimpaUsuariosInativos implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    Thread.sleep(10000);
                    for (Map.Entry<InetAddress, Long> entry : ultimasSondas.entrySet()) {
                        InetAddress enderecoInativo = entry.getKey();
                        long ultimoTimestamp = entry.getValue();
                        if (System.currentTimeMillis() - ultimoTimestamp > 30000) {
                            Usuario remover = usuariosOnline.get(enderecoInativo);
                            if (remover != null && usuarioListener != null) {
                                usuarioListener.usuarioRemovido(remover);
                            }
                            usuariosOnline.remove(enderecoInativo);
                            ultimasSondas.remove(enderecoInativo);
                        }
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void processarPacote(DatagramPacket packet) throws IOException {
        String strMensagem = new String(packet.getData(), 0, packet.getLength());
        ObjectMapper mapper = new ObjectMapper();
        Mensagem mensagemRecebida = mapper.readValue(strMensagem, Mensagem.class);

        if (usuario != null && packet.getAddress().equals(usuario.getEndereco())) return;

        if (mensagemRecebida.getTipoMensagem() == Mensagem.TipoMensagem.sonda) {
            Usuario usuarioSonda = new Usuario();
            usuarioSonda.setNome(mensagemRecebida.getUsuario());
            usuarioSonda.setStatus(getStatusOrDefault(mensagemRecebida.getStatus()));
            usuarioSonda.setEndereco(packet.getAddress());

            ultimasSondas.put(usuarioSonda.getEndereco(), System.currentTimeMillis());
            usuariosOnline.put(usuarioSonda.getEndereco(), usuarioSonda);

            if (usuarioListener != null) {
                usuarioListener.usuarioAlterado(usuarioSonda);
            }

        } else if (mensagemRecebida.getTipoMensagem() == Mensagem.TipoMensagem.msg_individual ||
                mensagemRecebida.getTipoMensagem() == Mensagem.TipoMensagem.msg_grupo) {
            if (mensagemListener != null) {
                Usuario remetente = new Usuario();
                remetente.setNome(mensagemRecebida.getUsuario());
                remetente.setStatus(getStatusOrDefault(mensagemRecebida.getStatus()));
                remetente.setEndereco(packet.getAddress());
                boolean isChatGeral = mensagemRecebida.getTipoMensagem() == Mensagem.TipoMensagem.msg_grupo;
                mensagemListener.mensagemRecebida(mensagemRecebida.getMsg(), remetente, isChatGeral);
            }
        } else if (mensagemRecebida.getTipoMensagem() == Mensagem.TipoMensagem.fim_chat) {
            if (mensagemListener != null) {
                Usuario remetente = new Usuario();
                remetente.setNome(mensagemRecebida.getUsuario());
                remetente.setEndereco(packet.getAddress());
                mensagemListener.fimChatPelaOutraParte(remetente);
            }
        }
    }

    private Usuario.StatusUsuario getStatusOrDefault(String statusString) {
        if (statusString == null || statusString.isEmpty()) return Usuario.StatusUsuario.DISPONIVEL;
        try {
            return Usuario.StatusUsuario.valueOf(statusString.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Usuario.StatusUsuario.DISPONIVEL;
        }
    }

    @Override
    public void enviarMensagem(String mensagem, Usuario destinatario, boolean chatGeral) {
        try {
            Mensagem msg = new Mensagem();
            msg.setUsuario(usuario.getNome());
            msg.setMsg(mensagem);
            msg.setStatus(usuario.getStatus().toString());
            msg.setTipoMensagem(chatGeral ? Mensagem.TipoMensagem.msg_grupo : Mensagem.TipoMensagem.msg_individual);

            ObjectMapper mapper = new ObjectMapper();
            byte[] bMensagem = mapper.writeValueAsBytes(msg);

            DatagramPacket packet;
            if (chatGeral) {
                for (int i = 1; i < 255; i++) {
                    packet = new DatagramPacket(bMensagem, bMensagem.length, InetAddress.getByName("192.168.0." + i), 8080);
                    DatagramSocket socket = new DatagramSocket();
                    socket.send(packet);
                    socket.close();
                }
            } else {
                packet = new DatagramPacket(bMensagem, bMensagem.length, destinatario.getEndereco(), 8080);
                DatagramSocket socket = new DatagramSocket();
                socket.send(packet);
                socket.close();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void usuarioAlterado(Usuario usuario) {
        this.usuario = usuario;
    }

    @Override
    public void addListenerMensagem(UDPServiceMensagemListener listener) {
        this.mensagemListener = listener;
    }

    @Override
    public void fimChat(Usuario usuario) {
        try {
            Mensagem msg = new Mensagem();
            msg.setUsuario(usuario.getNome());
            msg.setTipoMensagem(Mensagem.TipoMensagem.fim_chat);

            ObjectMapper mapper = new ObjectMapper();
            byte[] bMensagem = mapper.writeValueAsBytes(msg);

            DatagramPacket packet = new DatagramPacket(bMensagem, bMensagem.length, usuario.getEndereco(), 8080);
            DatagramSocket socket = new DatagramSocket();
            socket.send(packet);
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void addListenerUsuario(UDPServiceUsuarioListener listener) {
        this.usuarioListener = listener;
    }
}
